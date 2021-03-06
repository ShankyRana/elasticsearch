/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.security.authz.accesscontrol;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.Bits;
import org.elasticsearch.core.internal.io.IOUtils;
import org.apache.lucene.util.TestUtil;
import org.elasticsearch.common.lucene.index.ElasticsearchDirectoryReader;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;
import org.elasticsearch.xpack.core.security.authz.accesscontrol.DocumentSubsetReader;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class DocumentSubsetReaderTests extends ESTestCase {

    private Directory directory;
    private DirectoryReader directoryReader;
    private BitsetFilterCache bitsetFilterCache;

    @Before
    public void setUpDirectory() {
        // We check it is empty at the end of the test, so make sure it is empty in the
        // beginning as well so that we can easily distinguish from garbage added by
        // this test and garbage not cleaned up by other tests.
        assertTrue(DocumentSubsetReader.NUM_DOCS_CACHE.toString(),
                DocumentSubsetReader.NUM_DOCS_CACHE.isEmpty());
        directory = newDirectory();
        IndexSettings settings = IndexSettingsModule.newIndexSettings("_index", Settings.EMPTY);
        bitsetFilterCache = new BitsetFilterCache(settings, new BitsetFilterCache.Listener() {
            @Override
            public void onCache(ShardId shardId, Accountable accountable) {

            }

            @Override
            public void onRemoval(ShardId shardId, Accountable accountable) {

            }
        });
    }

    @After
    public void cleanDirectory() throws Exception {
        if (directoryReader != null) {
            directoryReader.close();
        }
        assertTrue(DocumentSubsetReader.NUM_DOCS_CACHE.toString(),
                DocumentSubsetReader.NUM_DOCS_CACHE.isEmpty());
        directory.close();
        bitsetFilterCache.close();
    }

    public void testSearch() throws Exception {
        IndexWriter iw = new IndexWriter(directory, newIndexWriterConfig());

        Document document = new Document();
        document.add(new StringField("field", "value1", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field", "value2", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field", "value3", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field", "value4", Field.Store.NO));
        iw.addDocument(document);

        iw.forceMerge(1);
        iw.deleteDocuments(new Term("field", "value3"));
        iw.close();
        openDirectoryReader();

        IndexSearcher indexSearcher = new IndexSearcher(DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache,
                new TermQuery(new Term("field", "value1"))));
        assertThat(indexSearcher.getIndexReader().numDocs(), equalTo(1));
        TopDocs result = indexSearcher.search(new MatchAllDocsQuery(), 1);
        assertThat(result.totalHits, equalTo(1L));
        assertThat(result.scoreDocs[0].doc, equalTo(0));

        indexSearcher = new IndexSearcher(DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache,
                new TermQuery(new Term("field", "value2"))));
        assertThat(indexSearcher.getIndexReader().numDocs(), equalTo(1));
        result = indexSearcher.search(new MatchAllDocsQuery(), 1);
        assertThat(result.totalHits, equalTo(1L));
        assertThat(result.scoreDocs[0].doc, equalTo(1));

        // this doc has been marked as deleted:
        indexSearcher = new IndexSearcher(DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache,
                new TermQuery(new Term("field", "value3"))));
        assertThat(indexSearcher.getIndexReader().numDocs(), equalTo(0));
        result = indexSearcher.search(new MatchAllDocsQuery(), 1);
        assertThat(result.totalHits, equalTo(0L));

        indexSearcher = new IndexSearcher(DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache,
                new TermQuery(new Term("field", "value4"))));
        assertThat(indexSearcher.getIndexReader().numDocs(), equalTo(1));
        result = indexSearcher.search(new MatchAllDocsQuery(), 1);
        assertThat(result.totalHits, equalTo(1L));
        assertThat(result.scoreDocs[0].doc, equalTo(3));
    }

    public void testLiveDocs() throws Exception {
        int numDocs = scaledRandomIntBetween(16, 128);
        IndexWriter iw = new IndexWriter(
                directory,
                new IndexWriterConfig(new StandardAnalyzer()).setMergePolicy(NoMergePolicy.INSTANCE)
        );

        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("field", "value" + i, Field.Store.NO));
            iw.addDocument(document);
        }

        iw.forceMerge(1);
        iw.close();

        openDirectoryReader();
        assertThat("should have one segment after force merge", directoryReader.leaves().size(), equalTo(1));

        for (int i = 0; i < numDocs; i++) {
            Query roleQuery = new TermQuery(new Term("field", "value" + i));
            DirectoryReader wrappedReader = DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache, roleQuery);

            LeafReader leafReader = wrappedReader.leaves().get(0).reader();
            assertThat(leafReader.hasDeletions(), is(true));
            assertThat(leafReader.numDocs(), equalTo(1));
            Bits liveDocs = leafReader.getLiveDocs();
            assertThat(liveDocs.length(), equalTo(numDocs));
            for (int docId = 0; docId < numDocs; docId++) {
                if (docId == i) {
                    assertThat("docId [" + docId +"] should match", liveDocs.get(docId), is(true));
                } else {
                    assertThat("docId [" + docId +"] should not match", liveDocs.get(docId), is(false));
                }
            }
        }
    }

    public void testWrapTwice() throws Exception {
        Directory dir = newDirectory();
        IndexWriterConfig iwc = new IndexWriterConfig(null);
        IndexWriter iw = new IndexWriter(dir, iwc);
        iw.close();
        IndexSettings settings = IndexSettingsModule.newIndexSettings("_index", Settings.EMPTY);
        BitsetFilterCache bitsetFilterCache = new BitsetFilterCache(settings, new BitsetFilterCache.Listener() {
            @Override
            public void onCache(ShardId shardId, Accountable accountable) {
            }

            @Override
            public void onRemoval(ShardId shardId, Accountable accountable) {
            }
        });
        DirectoryReader directoryReader = DocumentSubsetReader.wrap(DirectoryReader.open(dir), bitsetFilterCache, new MatchAllDocsQuery());
        try {
            DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache, new MatchAllDocsQuery());
            fail("shouldn't be able to wrap DocumentSubsetDirectoryReader twice");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("Can't wrap [class org.elasticsearch.xpack.core.security.authz.accesscontrol" +
                    ".DocumentSubsetReader$DocumentSubsetDirectoryReader] twice"));
        }

        bitsetFilterCache.close();
        directoryReader.close();
        dir.close();
    }

    /** Same test as in FieldSubsetReaderTests, test that core cache key (needed for NRT) is working */
    public void testCoreCacheKey() throws Exception {
        Directory dir = newDirectory();
        IndexWriterConfig iwc = new IndexWriterConfig(null);
        iwc.setMaxBufferedDocs(100);
        iwc.setMergePolicy(NoMergePolicy.INSTANCE);
        IndexWriter iw = new IndexWriter(dir, iwc);

        // add two docs, id:0 and id:1
        Document doc = new Document();
        Field idField = new StringField("id", "", Field.Store.NO);
        doc.add(idField);
        idField.setStringValue("0");
        iw.addDocument(doc);
        idField.setStringValue("1");
        iw.addDocument(doc);

        // open reader
        DirectoryReader ir = ElasticsearchDirectoryReader.wrap(DirectoryReader.open(iw), new ShardId("_index", "_na_", 0));
        ir = DocumentSubsetReader.wrap(ir, bitsetFilterCache, new MatchAllDocsQuery());
        assertEquals(2, ir.numDocs());
        assertEquals(1, ir.leaves().size());

        // delete id:0 and reopen
        iw.deleteDocuments(new Term("id", "0"));
        DirectoryReader ir2 = DirectoryReader.openIfChanged(ir);

        // we should have the same cache key as before
        assertEquals(1, ir2.numDocs());
        assertEquals(1, ir2.leaves().size());
        assertSame(ir.leaves().get(0).reader().getCoreCacheHelper().getKey(),
                ir2.leaves().get(0).reader().getCoreCacheHelper().getKey());
        // However we don't support caching on the reader cache key since we override deletes
        assertNull(ir.leaves().get(0).reader().getReaderCacheHelper());
        assertNull(ir2.leaves().get(0).reader().getReaderCacheHelper());

        TestUtil.checkReader(ir);
        IOUtils.close(ir, ir2, iw, dir);
    }

    private void openDirectoryReader() throws IOException {
        directoryReader = DirectoryReader.open(directory);
        directoryReader = ElasticsearchDirectoryReader.wrap(directoryReader, new ShardId("_index", "_na_", 0));
    }
}
