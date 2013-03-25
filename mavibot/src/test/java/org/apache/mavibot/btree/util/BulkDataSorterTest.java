/*
 *   Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */
package org.apache.mavibot.btree.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Random;

import org.apache.mavibot.btree.Tuple;
import org.junit.Test;

/**
 * Test cases for BulkDataSorter.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class BulkDataSorterTest
{

    private Comparator<Tuple<Integer, Integer>> tupleComp = new Comparator<Tuple<Integer, Integer>>()
    {
        
        @Override
        public int compare( Tuple<Integer, Integer> o1, Tuple<Integer, Integer> o2 )
        {
            return o1.getKey().compareTo( o2.getKey() );
        }
    };

    
    @Test
    public void testSortedFileCount() throws IOException
    {
        int count = 7;
        IntTupleReaderWriter itrw = new IntTupleReaderWriter();
        Random random = new Random();
        
        File dataFile = File.createTempFile( "tuple", ".data" );
        dataFile.deleteOnExit();
        DataOutputStream out = new DataOutputStream( new FileOutputStream( dataFile ) );
        
        Tuple<Integer, Integer>[] arr = (Tuple<Integer, Integer>[]) Array.newInstance( Tuple.class, count );
        
        for ( int i = 0; i < count; i++ )
        {
            int x = random.nextInt(100);
            //System.out.println(x);

            Tuple<Integer, Integer> t = new Tuple<Integer, Integer>( x, x );
            
            arr[i] = t;
            
            itrw.writeTuple( t, out );
        }

        out.close();
        
        BulkDataSorter<Integer, Integer> bds = new BulkDataSorter<Integer, Integer>( itrw, tupleComp, 4 );
        bds.sort( dataFile );
        
        assertEquals(2, bds.getWorkDir().list().length);
        
        deleteDir( bds.getWorkDir() );
    }
    
    @Test
    public void testSortedFileMerge() throws IOException
    {
        testSortedFileMerge( 10, 2 );
        testSortedFileMerge( 100, 7 );
        testSortedFileMerge( 1000, 25 );
        testSortedFileMerge( 10000, 100 );
        testSortedFileMerge( 10000, 101 );
        testSortedFileMerge( 100000, 501 );
    }
    
    public void testSortedFileMerge(int count, int splitAfter) throws IOException
    {
        IntTupleReaderWriter itrw = new IntTupleReaderWriter();
        Random random = new Random();
        
        File dataFile = File.createTempFile( "tuple", ".data" );
        dataFile.deleteOnExit();
        
        DataOutputStream out = new DataOutputStream( new FileOutputStream( dataFile ) );
        
        Tuple<Integer, Integer>[] arr = (Tuple<Integer, Integer>[]) Array.newInstance( Tuple.class, count );
        
        int randUpper = count;
        if(count < 100)
        {
            randUpper = 100;
        }
        
        for ( int i = 0; i < count; i++ )
        {
            int x = random.nextInt(randUpper);
            //System.out.println(x);

            Tuple<Integer, Integer> t = new Tuple<Integer, Integer>( x, x );
            
            arr[i] = t;
            
            itrw.writeTuple( t, out );
        }

        out.close();
        
        BulkDataSorter<Integer, Integer> bds = new BulkDataSorter<Integer, Integer>( itrw, tupleComp, splitAfter );
        bds.sort( dataFile );
        
        Iterator<Tuple<Integer,Integer>> itr = bds.getMergeSortedTuples();
        
        Integer prev = null;
        while(itr.hasNext())
        {
            Tuple<Integer,Integer> t = itr.next();
            
            if(prev == null)
            {
                prev = t.getKey();
            }
            else
            {
                assertTrue( prev <= t.getKey() );
            }
            
            //System.out.println(t);
        }
        
        deleteDir( bds.getWorkDir() );
    }
    
    
    private void deleteDir(File dir)
    {
        if(dir.isFile())
        {
            dir.delete();
        }
        
        File[] files = dir.listFiles();
        
        for(File f: files)
        {
            f.delete();
        }
        
        dir.delete();
    }
}