/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mavibot.btree;


import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.mavibot.btree.exception.KeyNotFoundException;
import org.apache.mavibot.btree.serializer.BufferHandler;
import org.apache.mavibot.btree.serializer.ElementSerializer;
import org.apache.mavibot.btree.serializer.LongSerializer;


/**
 * The B+Tree MVCC data structure.
 * 
 * @param <K> The type for the keys
 * @param <V> The type for the stored values
 *
 * @author <a href="mailto:labs@labs.apache.org">Mavibot labs Project</a>
 */
public class BTree<K, V>
{
    /** Default page size (number of entries per node) */
    public static final int DEFAULT_PAGE_SIZE = 16;

    /** Default size of the buffer used to write data n disk. Around 1Mb */
    public static final int DEFAULT_WRITE_BUFFER_SIZE = 4096 * 250;

    /** The default journal name */
    public static final String DEFAULT_JOURNAL = "mavibot.log";

    /** The default data file suffix */
    public static final String DATA_SUFFIX = ".data";

    /** The default journal file suffix */
    public static final String JOURNAL_SUFFIX = ".log";

    /** The BTree name */
    private String name;

    /** A field used to generate new revisions in a thread safe way */
    private AtomicLong revision;

    /** A field used to generate new recordId in a thread safe way */
    private transient AtomicLong pageRecordIdGenerator;

    /** Comparator used to index entries. */
    private Comparator<K> comparator;

    /** The current rootPage */
    protected volatile Page<K, V> rootPage;

    /** The list of read transactions being executed */
    private ConcurrentLinkedQueue<Transaction<K, V>> readTransactions;

    /** Number of entries in each Page. */
    protected int pageSize;

    /** The size of the buffer used to write data in disk */
    private int writeBufferSize;

    /** The type to use to create the keys */
    protected Class<?> keyType;

    /** The Key serializer used for this tree.*/
    private ElementSerializer<K> keySerializer;

    /** The Value serializer used for this tree. */
    private ElementSerializer<V> valueSerializer;

    /** The associated file. If null, this is an in-memory btree  */
    private File file;

    /** A flag set to true when the BTree is a in-memory BTree */
    private boolean inMemory;

    /** A flag used to tell the BTree that the journal is activated */
    private boolean withJournal;

    /** The associated journal. If null, this is an in-memory btree  */
    private File journal;

    /** The number of elements in the current revision */
    private AtomicLong nbElems;

    /** A lock used to protect the write operation against concurrent access */
    private ReentrantLock writeLock;

    /** The thread responsible for the cleanup of timed out reads */
    private Thread readTransactionsThread;

    /** The thread responsible for the journal updates */
    private Thread journalManagerThread;

    /** Define a default delay for a read transaction. This is 10 seconds */
    public static final long DEFAULT_READ_TIMEOUT = 10 * 1000L;

    /** The read transaction timeout */
    private long readTimeOut = DEFAULT_READ_TIMEOUT;

    /** The queue containing all the modifications applied on the bTree */
    private BlockingQueue<Modification<K, V>> modificationsQueue;


    /**
     * Create a thread that is responsible of cleaning the transactions when
     * they hit the timeout
     */
    private void createTransactionManager()
    {
        Runnable readTransactionTask = new Runnable()
        {
            public void run()
            {
                try
                {
                    Transaction<K, V> transaction = null;

                    while ( !Thread.currentThread().isInterrupted() )
                    {
                        long timeoutDate = System.currentTimeMillis() - readTimeOut;
                        long t0 = System.currentTimeMillis();
                        int nbTxns = 0;

                        // Loop on all the transactions from the queue
                        while ( ( transaction = readTransactions.peek() ) != null )
                        {
                            nbTxns++;

                            if ( transaction.isClosed() )
                            {
                                // The transaction is already closed, remove it from the queue
                                readTransactions.poll();
                                continue;
                            }

                            // Check if the transaction has timed out
                            if ( transaction.getCreationDate() < timeoutDate )
                            {
                                transaction.close();
                                readTransactions.poll();
                                continue;
                            }

                            // We need to stop now
                            break;
                        }

                        long t1 = System.currentTimeMillis();

                        if ( nbTxns > 0 )
                        {
                            System.out.println( "Processing old txn : " + nbTxns + ", " + ( t1 - t0 ) + "ms" );
                        }

                        // Wait until we reach the timeout
                        Thread.sleep( readTimeOut );
                    }
                }
                catch ( InterruptedException ie )
                {
                    //System.out.println( "Interrupted" );
                }
                catch ( Exception e )
                {
                    throw new RuntimeException( e );
                }
            }
        };

        readTransactionsThread = new Thread( readTransactionTask );
        readTransactionsThread.setDaemon( true );
        readTransactionsThread.start();
    }


    /**
     * Create a thread that is responsible of writing the modifications in a journal.
     * The journal will contain all the modifications in the order they have been applied
     * to the BTree. We will store Insertions and Deletions. Those operations are injected
     * into a queue, which is read by the thread.
     */
    private void createJournalManager()
    {
        Runnable journalTask = new Runnable()
        {
            private boolean flushModification( FileChannel channel, Modification<K, V> modification )
                throws IOException
            {
                if ( modification instanceof Addition )
                {
                    byte[] keyBuffer = keySerializer.serialize( modification.getKey() );
                    ByteBuffer bb = ByteBuffer.allocateDirect( keyBuffer.length + 1 );
                    bb.put( Modification.ADDITION );
                    bb.put( keyBuffer );
                    bb.flip();

                    channel.write( bb );

                    byte[] valueBuffer = valueSerializer.serialize( modification.getValue() );
                    bb = ByteBuffer.allocateDirect( valueBuffer.length );
                    bb.put( valueBuffer );
                    bb.flip();

                    channel.write( bb );
                }
                else if ( modification instanceof Deletion )
                {
                    byte[] keyBuffer = keySerializer.serialize( modification.getKey() );
                    ByteBuffer bb = ByteBuffer.allocateDirect( keyBuffer.length + 1 );
                    bb.put( Modification.DELETION );
                    bb.put( keyBuffer );
                    bb.flip();

                    channel.write( bb );
                }
                else
                // This is the poison pill, just exit
                {
                    return false;
                }

                // Flush to the disk for real
                channel.force( true );

                return true;
            }


            public void run()
            {
                Modification<K, V> modification = null;
                FileOutputStream stream;
                FileChannel channel = null;

                try
                {
                    stream = new FileOutputStream( journal );
                    channel = stream.getChannel();

                    while ( !Thread.currentThread().isInterrupted() )
                    {
                        modification = modificationsQueue.take();

                        boolean stop = flushModification( channel, modification );

                        if ( stop )
                        {
                            break;
                        }
                    }
                }
                catch ( InterruptedException ie )
                {
                    //System.out.println( "Interrupted" );
                    while ( ( modification = modificationsQueue.peek() ) != null );

                    try
                    {
                        flushModification( channel, modification );
                    }
                    catch ( IOException ioe )
                    {
                        // There is little we can do here...
                    }
                }
                catch ( Exception e )
                {
                    throw new RuntimeException( e );
                }
            }
        };

        journalManagerThread = new Thread( journalTask );
        journalManagerThread.setDaemon( true );
        journalManagerThread.start();
    }


    /**
     * Creates a new in-memory BTree using the BTreeConfiguration to initialize the 
     * BTree
     * 
     * @param comparator The comparator to use
     */
    public BTree( BTreeConfiguration<K, V> configuration ) throws IOException
    {
        String fileName = configuration.getFileName();
        String journalName = configuration.getJournalName();

        if ( fileName == null )
        {
            inMemory = true;
        }
        else
        {
            file = new File( configuration.getFilePath(), fileName );

            String journalPath = configuration.getJournalPath();

            if ( journalPath == null )
            {
                journalPath = configuration.getFilePath();
            }

            if ( journalName == null )
            {
                journalName = fileName + JOURNAL_SUFFIX;
            }

            journal = new File( journalPath, journalName );
            inMemory = false;
        }

        pageSize = configuration.getPageSize();
        keySerializer = configuration.getKeySerializer();
        valueSerializer = configuration.getValueSerializer();
        comparator = keySerializer.getComparator();
        readTimeOut = configuration.getReadTimeOut();
        writeBufferSize = configuration.getWriteBufferSize();

        if ( comparator == null )
        {
            throw new IllegalArgumentException( "Comparator should not be null" );
        }

        // Now, initialize the BTree
        init();
    }


    /**
     * Creates a new in-memory BTree with a default page size and key/value serializers.
     * 
     * @param comparator The comparator to use
     */
    public BTree( String name, ElementSerializer<K> keySerializer, ElementSerializer<V> valueSerializer )
        throws IOException
    {
        this( name, null, null, keySerializer, valueSerializer, DEFAULT_PAGE_SIZE );
    }


    /**
     * Creates a new in-memory BTree with a default page size and key/value serializers.
     * 
     * @param comparator The comparator to use
     */
    public BTree( String name, ElementSerializer<K> keySerializer, ElementSerializer<V> valueSerializer, int pageSize )
        throws IOException
    {
        this( name, null, null, keySerializer, valueSerializer, pageSize );
    }


    /**
     * Creates a new BTree with a default page size and a comparator, with an associated file.
     * 
     * @param file The file storing the BTree data
     * @param comparator The comparator to use
     * @param serializer The serializer to use
     */
    public BTree( String name, String path, String file, ElementSerializer<K> keySerializer,
        ElementSerializer<V> valueSerializer )
        throws IOException
    {
        this( name, path, file, keySerializer, valueSerializer, DEFAULT_PAGE_SIZE );
    }


    /**
     * Creates a new BTree with a specific page size and a comparator, with an associated file.
     * 
     * @param file The file storing the BTree data
     * @param comparator The comparator to use
     * @param serializer The serializer to use
     * @param pageSize The number of elements we can store in a page
     */
    public BTree( String name, String path, String file, ElementSerializer<K> keySerializer,
        ElementSerializer<V> valueSerializer,
        int pageSize )
        throws IOException
    {
        this.name = name;

        if ( ( path == null ) && ( file == null ) )
        {
            inMemory = true;
        }
        else
        {
            if ( new File( path, file ).exists() )
            {
                this.file = new File( path, file );
            }
            else
            {
                this.file = new File( path, file + DATA_SUFFIX );
            }

            if ( new File( path, file + JOURNAL_SUFFIX ).exists() )
            {
                this.journal = new File( path, file );
            }
            else
            {
                this.journal = new File( path, file + JOURNAL_SUFFIX );
            }

            inMemory = false;
        }

        setPageSize( pageSize );
        writeBufferSize = DEFAULT_WRITE_BUFFER_SIZE;

        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        comparator = keySerializer.getComparator();

        // Now, call the init() method
        init();
    }


    /**
     * Initialize the BTree.
     * 
     * @throws IOException If we get some exception while initializing the BTree
     */
    public void init() throws IOException
    {
        // Create the queue containing the pending read transactions
        readTransactions = new ConcurrentLinkedQueue<Transaction<K, V>>();

        // Create the queue containing the modifications, if it's not a in-memory btree
        if ( !inMemory )
        {
            modificationsQueue = new LinkedBlockingDeque<Modification<K, V>>();
        }

        // Initialize the PageId counter
        pageRecordIdGenerator = new AtomicLong( 0 );

        // Initialize the revision counter
        revision = new AtomicLong( 0 );

        // Create the first root page, with revision 0L. It will be empty
        // and increment the revision at the same time
        rootPage = new Leaf<K, V>( this );

        // We will extract the Type to use for keys, using the comparator for that
        Class<?> comparatorClass = comparator.getClass();
        Type[] types = comparatorClass.getGenericInterfaces();
        Type[] argumentTypes = ( ( ParameterizedType ) types[0] ).getActualTypeArguments();

        if ( ( argumentTypes != null ) && ( argumentTypes.length > 0 ) && ( argumentTypes[0] instanceof Class<?> ) )
        {
            keyType = ( Class<?> ) argumentTypes[0];
        }

        nbElems = new AtomicLong( 0 );
        writeLock = new ReentrantLock();

        // Check the files and create them if missing
        if ( file != null )
        {
            if ( !file.exists() )
            {
                file.createNewFile();

                if ( journal == null )
                {
                    journal = new File( file.getParentFile(), BTree.DEFAULT_JOURNAL );
                }

                journal.createNewFile();
                withJournal = true;

                // If the journal is not empty, we have to read it
                // and to apply all the modifications to the current file
                if ( journal.length() > 0 )
                {
                    applyJournal();
                }
            }
            else
            {
                if ( file.length() > 0 )
                {
                    // We have some existing file, load it 
                    load( file );
                }

                if ( journal == null )
                {
                    journal = new File( file.getParentFile(), BTree.DEFAULT_JOURNAL );
                }

                journal.createNewFile();
                withJournal = true;

                // If the journal is not empty, we have to read it
                // and to apply all the modifications to the current file
                if ( journal.length() > 0 )
                {
                    applyJournal();
                }
            }
        }
        else
        {
            withJournal = false;
        }

        // Initialize the txnManager thread
        createTransactionManager();

        // Initialize the Journal manager thread if it's not a in-memory btree
        if ( !inMemory && withJournal )
        {
            createJournalManager();
        }
    }


    /**
     * Close the BTree, cleaning up all the data structure
     */
    public void close() throws IOException
    {
        // Stop the readTransaction thread
        readTransactionsThread.interrupt();
        readTransactions.clear();

        if ( !inMemory )
        {
            // Stop the journal manager thread, by injecting a poison pill into
            // the queue this thread is using, so that all the epnding data
            // will be written before it shuts down
            modificationsQueue.add( new PoisonPill<K, V>() );

            // Flush the data
            flush();
        }

        rootPage = null;
    }


    /**
     * Gets the number which is a power of 2 immediately above the given positive number.
     */
    private int getPowerOf2( int size )
    {
        int newSize = --size;
        newSize |= newSize >> 1;
        newSize |= newSize >> 2;
        newSize |= newSize >> 4;
        newSize |= newSize >> 8;
        newSize |= newSize >> 16;
        newSize++;

        return newSize;
    }


    /**
     * Set the maximum number of elements we can store in a page. This must be a
     * number greater than 1, and a power of 2. The default page size is 16.
     * <br/>
     * If the provided size is below 2, we will default to DEFAULT_PAGE_SIZE.<br/>
     * If the provided size is not a power of 2, we will select the closest power of 2
     * higher than the given number<br/>
     * 
     * @param pageSize The requested page size
     */
    public void setPageSize( int pageSize )
    {
        this.pageSize = pageSize;

        if ( pageSize <= 2 )
        {
            this.pageSize = DEFAULT_PAGE_SIZE;
        }

        this.pageSize = getPowerOf2( pageSize );
    }


    /**
     * Set the new root page for this tree. Used for debug purpose only. The revision
     * will always be 0;
     * 
     * @param root the new root page.
     */
    /* No qualifier */void setRoot( Page<K, V> root )
    {
        rootPage = root;
    }


    /**
     * @return the pageSize
     */
    public int getPageSize()
    {
        return pageSize;
    }


    /**
     * Generates a new RecordId. It's only used by the Page instances.
     * 
     * @return a new incremental recordId
     */
    /** No qualifier */
    long generateRecordId()
    {
        return pageRecordIdGenerator.getAndIncrement();
    }


    /**
     * Generates a new revision number. It's only used by the Page instances.
     * 
     * @return a new incremental revision number
     */
    /** No qualifier */
    long generateRevision()
    {
        return revision.getAndIncrement();
    }


    /**
     * Insert an entry in the BTree.
     * <p>
     * We will replace the value if the provided key already exists in the
     * btree.
     *
     * @param key Inserted key
     * @param value Inserted value
     * @return Existing value, if any.
     * @throws IOException TODO
     */
    public V insert( K key, V value ) throws IOException
    {
        long revision = generateRevision();

        V existingValue = insert( key, value, revision );

        // Increase the number of element in the current tree if the insertion is successful
        // and does not replace an element
        if ( existingValue == null )
        {
            nbElems.getAndIncrement();
        }

        return existingValue;
    }


    /**
     * Delete the entry which key is given as a parameter. If the entry exists, it will
     * be removed from the tree, the old tuple will be returned. Otherwise, null is returned.
     * 
     * @param key The key for the entry we try to remove
     * @return A Tuple<K, V> containing the removed entry, or null if it's not found.
     */
    public Tuple<K, V> delete( K key ) throws IOException
    {
        if ( key == null )
        {
            throw new IllegalArgumentException( "Key must not be null" );
        }

        long revision = generateRevision();

        Tuple<K, V> deleted = delete( key, revision );

        // Decrease the number of element in the current tree if the delete is successful
        if ( deleted != null )
        {
            nbElems.getAndDecrement();
        }

        return deleted;
    }


    /**
     * Delete the value from an entry which key is given as a parameter. If the value
     * is present, we will return it. If the remaining entry is empty, we will remove it
     * from the tree.
     * 
     * @param key The key for the entry we try to remove
     * @return A Tuple<K, V> containing the removed entry, or null if it's not found.
     */
    public Tuple<K, V> delete( K key, V value ) throws IOException
    {
        if ( key == null )
        {
            throw new IllegalArgumentException( "Key must not be null" );
        }

        if ( value == null )
        {
            throw new IllegalArgumentException( "Value must not be null" );
        }

        long revision = generateRevision();

        Tuple<K, V> deleted = delete( key, revision );

        // Decrease the number of element in the current tree if the delete is successful
        if ( deleted != null )
        {
            nbElems.getAndDecrement();
        }

        return deleted;
    }


    /**
     * Delete the entry which key is given as a parameter. If the entry exists, it will
     * be removed from the tree, the old tuple will be returned. Otherwise, null is returned.
     * 
     * @param key The key for the entry we try to remove
     * @return A Tuple<K, V> containing the removed entry, or null if it's not found.
     */
    private Tuple<K, V> delete( K key, long revision ) throws IOException
    {
        writeLock.lock();

        try
        {
            // If the key exists, the existing value will be replaced. We store it
            // to return it to the caller.
            Tuple<K, V> tuple = null;

            // Try to delete the entry starting from the root page. Here, the root
            // page may be either a Node or a Leaf
            DeleteResult<K, V> result = rootPage.delete( revision, key, null, -1 );

            if ( result instanceof NotPresentResult )
            {
                // Key not found.
                return null;
            }

            if ( result instanceof RemoveResult )
            {
                // The element was found, and removed
                RemoveResult<K, V> removeResult = ( RemoveResult<K, V> ) result;

                Page<K, V> newPage = removeResult.getModifiedPage();

                // This is a new root
                rootPage = newPage;
                tuple = removeResult.getRemovedElement();
            }

            if ( !inMemory )
            {
                // Inject the modification into the modification queue
                modificationsQueue.add( new Deletion<K, V>( key ) );
            }

            // Return the value we have found if it was modified
            return tuple;
        }
        finally
        {
            // See above
            writeLock.unlock();
        }
    }


    /**
     * Check if there is an element associated with the given key.
     * 
     * @param key The key we are looking at
     * @return true if the Key exists in the BTree 
     * @throws IOException 
     */
    public boolean exist( K key ) throws IOException
    {
        return rootPage.exist( key );
    }


    /**
     * Find a value in the tree, given its key. If the key is not found,
     * it will throw a KeyNotFoundException. <br/>
     * Note that we can get a null value stored, or many values.
     * 
     * @param key The key we are looking at
     * @return The found value, or null if the key is not present in the tree
     * @throws KeyNotFoundException If the key is not found in the BTree
     * @throws IOException TODO
     */
    public V get( K key ) throws IOException, KeyNotFoundException
    {
        return rootPage.get( key );
    }


    /**
     * Creates a cursor starting on the given key
     * 
     * @param key The key which is the starting point. If the key is not found,
     * then the cursor will always return null.
     * @return A cursor on the btree
     * @throws IOException
     */
    public Cursor<K, V> browse( K key ) throws IOException
    {
        Transaction<K, V> transaction = beginReadTransaction();

        // Fetch the root page for this revision
        Page<K, V> root = rootPage;
        Cursor<K, V> cursor = root.browse( key, transaction, new LinkedList<ParentPos<K, V>>() );

        return cursor;
    }


    /**
     * Creates a cursor starting at the beginning of the tree
     * 
     * @return A cursor on the btree
     * @throws IOException
     */
    public Cursor<K, V> browse() throws IOException
    {
        Transaction<K, V> transaction = beginReadTransaction();

        // Fetch the root page for this revision
        Page<K, V> root = rootPage;
        LinkedList<ParentPos<K, V>> stack = new LinkedList<ParentPos<K, V>>();

        Cursor<K, V> cursor = root.browse( transaction, stack );

        return cursor;
    }


    /**
     * Insert an entry in the BTree.
     * <p>
     * We will replace the value if the provided key already exists in the
     * btree.
     * <p>
     * The revision number is the revision to use to insert the data.
     *
     * @param key Inserted key
     * @param value Inserted value
     * @param revision The revision to use
     * @return Existing value, if any.
     */
    private V insert( K key, V value, long revision ) throws IOException
    {
        if ( key == null )
        {
            throw new IllegalArgumentException( "Key must not be null" );
        }

        // Commented atm, we will have to play around the idea of transactions later
        writeLock.lock();

        try
        {
            // If the key exists, the existing value will be replaced. We store it
            // to return it to the caller.
            V modifiedValue = null;

            // Try to insert the new value in the tree at the right place,
            // starting from the root page. Here, the root page may be either
            // a Node or a Leaf
            InsertResult<K, V> result = rootPage.insert( revision, key, value );

            if ( result instanceof ModifyResult )
            {
                ModifyResult<K, V> modifyResult = ( ( ModifyResult<K, V> ) result );

                // The root has just been modified, we haven't split it
                // Get it and make it the current root page
                rootPage = modifyResult.getModifiedPage();

                modifiedValue = modifyResult.getModifiedValue();
            }
            else
            {
                // We have split the old root, create a new one containing
                // only the pivotal we got back
                SplitResult<K, V> splitResult = ( ( SplitResult<K, V> ) result );

                K pivot = splitResult.getPivot();
                Page<K, V> leftPage = splitResult.getLeftPage();
                Page<K, V> rightPage = splitResult.getRightPage();

                // Create the new rootPage
                rootPage = new Node<K, V>( this, revision, pivot, leftPage, rightPage );
            }

            // Inject the modification into the modification queue
            if ( !inMemory )
            {
                modificationsQueue.add( new Addition<K, V>( key, value ) );
            }

            // Return the value we have found if it was modified
            return modifiedValue;
        }
        finally
        {
            // See above
            writeLock.unlock();
        }
    }


    /**
     * Starts a Read Only transaction. If the transaction is not closed, it will be 
     * automatically closed after the timeout
     * @return The created transaction
     */
    private Transaction<K, V> beginReadTransaction()
    {
        Transaction<K, V> readTransaction = new Transaction<K, V>( rootPage, revision.get() - 1,
            System.currentTimeMillis() );

        readTransactions.add( readTransaction );

        return readTransaction;
    }


    /**
     * @return the type for the keys
     */
    public Class<?> getKeyType()
    {
        return keyType;
    }


    /**
     * @return the comparator
     */
    public Comparator<K> getComparator()
    {
        return comparator;
    }


    /**
     * @param comparator the comparator to set
     */
    public void setComparator( Comparator<K> comparator )
    {
        this.comparator = comparator;
    }


    /**
     * @param keySerializer the Key serializer to set
     */
    public void setKeySerializer( ElementSerializer<K> keySerializer )
    {
        this.keySerializer = keySerializer;
    }


    /**
     * @param valueSerializer the Value serializer to set
     */
    public void setValueSerializer( ElementSerializer<V> valueSerializer )
    {
        this.valueSerializer = valueSerializer;
    }


    /**
     * Write the data in the ByteBuffer, and eventually on disk if needed.
     * 
     * @param channel The channel we want to write to
     * @param bb The ByteBuffer we want to feed
     * @param buffer The data to inject
     * @throws IOException If the write failed
     */
    private void writeBuffer( FileChannel channel, ByteBuffer bb, byte[] buffer ) throws IOException
    {
        int size = buffer.length;
        int pos = 0;

        // Loop until we have written all the data
        do
        {
            if ( bb.remaining() >= size )
            {
                // No flush, as the ByteBuffer is big enough
                bb.put( buffer, pos, size );
                size = 0;
            }
            else
            {
                // Flush the data on disk, reinitialize the ByteBuffer
                int len = bb.remaining();
                size -= len;
                bb.put( buffer, pos, len );
                pos += len;

                bb.flip();

                channel.write( bb );

                bb.clear();
            }
        }
        while ( size > 0 );
    }


    /**
     * Flush the latest revision to disk
     * @param file The file into which the data will be written
     */
    public void flush( File file ) throws IOException
    {
        File parentFile = file.getParentFile();
        File baseDirectory = null;

        if ( parentFile != null )
        {
            baseDirectory = new File( file.getParentFile().getAbsolutePath() );
        }
        else
        {
            baseDirectory = new File( "." );
        }

        // Create a temporary file in the same directory to flush the current btree
        File tmpFileFD = File.createTempFile( "mavibot", null, baseDirectory );
        FileOutputStream stream = new FileOutputStream( tmpFileFD );
        FileChannel ch = stream.getChannel();

        // Create a buffer containing 200 4Kb pages (around 1Mb)
        ByteBuffer bb = ByteBuffer.allocateDirect( writeBufferSize );

        Cursor<K, V> cursor = browse();

        if ( keySerializer == null )
        {
            throw new RuntimeException( "Cannot flush the btree without a Key serializer" );
        }

        if ( valueSerializer == null )
        {
            throw new RuntimeException( "Cannot flush the btree without a Value serializer" );
        }

        // Write the number of elements first
        bb.putLong( nbElems.get() );

        while ( cursor.hasNext() )
        {
            Tuple<K, V> tuple = cursor.next();

            byte[] keyBuffer = keySerializer.serialize( tuple.getKey() );

            writeBuffer( ch, bb, keyBuffer );

            byte[] valueBuffer = valueSerializer.serialize( tuple.getValue() );

            writeBuffer( ch, bb, valueBuffer );
        }

        // Write the buffer if needed
        if ( bb.position() > 0 )
        {
            bb.flip();
            ch.write( bb );
        }

        // Flush to the disk for real
        ch.force( true );
        ch.close();

        // Rename the current file to save a backup
        File backupFile = File.createTempFile( "mavibot", null, baseDirectory );
        file.renameTo( backupFile );

        // Rename the temporary file to the initial file
        tmpFileFD.renameTo( file );

        // We can now delete the backup file
        backupFile.delete();
    }


    /** 
     * Inject all the modification from the journal into the btree
     * 
     * @throws IOException If we had some issue while reading the journal
     */
    private void applyJournal() throws IOException
    {
        long revision = generateRevision();

        if ( !journal.exists() )
        {
            throw new IOException( "The journal does not exist" );
        }

        FileChannel channel =
            new RandomAccessFile( journal, "rw" ).getChannel();
        ByteBuffer buffer = ByteBuffer.allocate( 65536 );

        BufferHandler bufferHandler = new BufferHandler( channel, buffer );

        // Loop on all the elements, store them in lists atm
        try
        {
            while ( true )
            {
                // Read the type 
                byte[] type = bufferHandler.read( 1 );

                if ( type[0] == Modification.ADDITION )
                {
                    // Read the key
                    K key = keySerializer.deserialize( bufferHandler );

                    //keys.add( key );

                    // Read the value
                    V value = valueSerializer.deserialize( bufferHandler );

                    //values.add( value );

                    // Inject the data in the tree. (to be replaced by a bulk load)
                    insert( key, value, revision );
                }
                else
                {
                    // Read the key
                    K key = keySerializer.deserialize( bufferHandler );

                    // Remove the key from the tree
                    delete( key, revision );
                }
            }
        }
        catch ( EOFException eofe )
        {
            // Done reading the journal. Delete it and recreate a new one
            journal.delete();
            journal.createNewFile();
        }
    }


    /**
     * Read the data from the disk into this BTree. All the existing data in the 
     * BTree are kept, the read data will be associated with a new revision.
     * 
     * @param file
     * @throws IOException
     */
    public void load( File file ) throws IOException
    {
        long revision = generateRevision();

        if ( !file.exists() )
        {
            throw new IOException( "The file does not exist" );
        }

        FileChannel channel =
            new RandomAccessFile( file, "rw" ).getChannel();
        ByteBuffer buffer = ByteBuffer.allocate( 65536 );

        BufferHandler bufferHandler = new BufferHandler( channel, buffer );

        long nbElems = LongSerializer.deserialize( bufferHandler.read( 8 ) );
        this.nbElems.set( nbElems );

        // Prepare a list of keys and values read from the disk
        //List<K> keys = new ArrayList<K>();
        //List<V> values = new ArrayList<V>();

        // desactivate the journal while we load the file
        boolean isJournalActivated = withJournal;

        withJournal = false;

        // Loop on all the elements, store them in lists atm
        for ( long i = 0; i < nbElems; i++ )
        {
            // Read the key
            K key = keySerializer.deserialize( bufferHandler );

            //keys.add( key );

            // Read the value
            V value = valueSerializer.deserialize( bufferHandler );

            //values.add( value );

            // Inject the data in the tree. (to be replaced by a bulk load)
            insert( key, value, revision );
        }

        // Restore the withJournal value
        withJournal = isJournalActivated;

        // Now, process the lists to create the btree
        // TODO... BulkLoad
    }


    /**
     * Flush the latest revision to disk. We will replace the current file by the new one, as
     * we flush in a temporary file.
     */
    public void flush() throws IOException
    {
        if ( !inMemory )
        {
            // Then flush the file
            flush( file );

            // And empty the journal
            FileOutputStream stream = new FileOutputStream( journal );
            FileChannel channel = stream.getChannel();
            channel.position( 0 );
            channel.force( true );
        }
    }


    /**
     * @return the readTimeOut
     */
    public long getReadTimeOut()
    {
        return readTimeOut;
    }


    /**
     * @param readTimeOut the readTimeOut to set
     */
    public void setReadTimeOut( long readTimeOut )
    {
        this.readTimeOut = readTimeOut;
    }


    /**
     * @return the name
     */
    public String getName()
    {
        return name;
    }


    /**
     * @param name the name to set
     */
    public void setName( String name )
    {
        this.name = name;
    }


    /**
     * @return the file
     */
    public File getFile()
    {
        return file;
    }


    /**
     * @return the journal
     */
    public File getJournal()
    {
        return journal;
    }


    /**
     * @return the writeBufferSize
     */
    public int getWriteBufferSize()
    {
        return writeBufferSize;
    }


    /**
     * @param writeBufferSize the writeBufferSize to set
     */
    public void setWriteBufferSize( int writeBufferSize )
    {
        this.writeBufferSize = writeBufferSize;
    }


    /**
     * @return the inMemory flag
     */
    public boolean isInMemory()
    {
        return inMemory;
    }


    /**
     * Create a ValueHolder depending on the kind of holder we want.
     * 
     * @param value The value to store
     * @return The value holder
     */
    /* no qualifier */ValueHolder<K, V> createHolder( V value )
    {
        if ( inMemory )
        {
            return new MemoryValueHolder<K, V>( value );
        }
        else
        {
            return new ReferenceValueHolder<K, V>( this, value );
        }
    }


    /**
     * @see Object#toString()
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        if ( inMemory )
        {
            sb.append( "In-memory " );
        }

        sb.append( "BTree" );
        sb.append( "( pageSize:" ).append( pageSize );

        if ( rootPage != null )
        {
            sb.append( ", nbEntries:" ).append( rootPage.getNbElems() );
        }
        else
        {
            sb.append( ", nbEntries:" ).append( 0 );
        }

        sb.append( ", comparator:" );

        if ( comparator == null )
        {
            sb.append( "null" );
        }
        else
        {
            sb.append( comparator.getClass().getSimpleName() );
        }

        if ( !inMemory )
        {
            try
            {
                sb.append( ", file : " );

                if ( file != null )
                {
                    sb.append( file.getCanonicalPath() );
                }
                else
                {
                    sb.append( "Unknown" );
                }

                sb.append( ", journal : " );

                if ( journal != null )
                {
                    sb.append( journal.getCanonicalPath() );
                }
                else
                {
                    sb.append( "Unkown" );
                }
            }
            catch ( IOException ioe )
            {
                // There is little we can do here...
            }
        }

        sb.append( ") : \n" );
        sb.append( rootPage.dumpPage( "" ) );

        return sb.toString();
    }
}
