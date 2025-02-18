/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene;

import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.AnyOperatorTestCase;
import org.elasticsearch.compute.operator.Driver;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.compute.operator.OperatorTestCase;
import org.elasticsearch.compute.operator.TestResultPageSinkOperator;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.cache.query.TrivialQueryCachingPolicy;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexFieldDataCache;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NestedLookup;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.query.support.NestedScope;
import org.elasticsearch.indices.CrankyCircuitBreakerService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.search.internal.ContextIndexSearcher;
import org.elasticsearch.search.internal.SearchContext;
import org.junit.After;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LuceneSourceOperatorTests extends AnyOperatorTestCase {
    private static final MappedFieldType S_FIELD = new NumberFieldMapper.NumberFieldType("s", NumberFieldMapper.NumberType.LONG);
    private Directory directory = newDirectory();
    private IndexReader reader;

    @After
    public void closeIndex() throws IOException {
        IOUtils.close(reader, directory);
    }

    @Override
    protected LuceneSourceOperator.Factory simple() {
        return simple(randomFrom(DataPartitioning.values()), between(1, 10_000), 100);
    }

    private LuceneSourceOperator.Factory simple(DataPartitioning dataPartitioning, int numDocs, int limit) {
        int commitEvery = Math.max(1, numDocs / 10);
        try (
            RandomIndexWriter writer = new RandomIndexWriter(
                random(),
                directory,
                newIndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE)
            )
        ) {
            for (int d = 0; d < numDocs; d++) {
                List<IndexableField> doc = new ArrayList<>();
                doc.add(new SortedNumericDocValuesField("s", d));
                writer.addDocument(doc);
                if (d % commitEvery == 0) {
                    writer.commit();
                }
            }
            reader = writer.getReader();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        SearchContext ctx = mockSearchContext(reader, 0);
        when(ctx.getSearchExecutionContext().getFieldType(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return switch (name) {
                case "s" -> S_FIELD;
                default -> throw new IllegalArgumentException("don't support [" + name + "]");
            };
        });
        when(ctx.getSearchExecutionContext().getForField(any(), any())).thenAnswer(inv -> {
            MappedFieldType ft = inv.getArgument(0);
            IndexFieldData.Builder builder = ft.fielddataBuilder(FieldDataContext.noRuntimeFields("test"));
            // This breaker is for fielddata from text fields. We don't test it so it won't break not test not to use a breaker here.
            return builder.build(new IndexFieldDataCache.None(), new NoneCircuitBreakerService());
        });
        when(ctx.getSearchExecutionContext().nestedScope()).thenReturn(new NestedScope());
        when(ctx.getSearchExecutionContext().nestedLookup()).thenReturn(NestedLookup.EMPTY);
        when(ctx.getSearchExecutionContext().getIndexReader()).thenReturn(reader);
        Function<SearchContext, Query> queryFunction = c -> new MatchAllDocsQuery();
        int maxPageSize = between(10, Math.max(10, numDocs));
        return new LuceneSourceOperator.Factory(List.of(ctx), queryFunction, dataPartitioning, 1, maxPageSize, limit);
    }

    @Override
    protected String expectedToStringOfSimple() {
        assumeFalse("can't support variable maxPageSize", true); // TODO allow testing this
        return "LuceneSourceOperator[shardId=0, maxPageSize=**random**]";
    }

    @Override
    protected String expectedDescriptionOfSimple() {
        assumeFalse("can't support variable maxPageSize", true); // TODO allow testing this
        return """
            LuceneSourceOperator[dataPartitioning = SHARD, maxPageSize = **random**, limit = 100, sorts = [{"s":{"order":"asc"}}]]""";
    }

    // TODO tests for the other data partitioning configurations

    public void testShardDataPartitioning() {
        int size = between(1_000, 20_000);
        int limit = between(10, size);
        testSimple(driverContext(), size, limit);
    }

    public void testEmpty() {
        testSimple(driverContext(), 0, between(10, 10_000));
    }

    public void testWithCranky() {
        try {
            testSimple(crankyDriverContext(), between(1, 10_000), 100);
            logger.info("cranky didn't break");
        } catch (CircuitBreakingException e) {
            logger.info("broken", e);
            assertThat(e.getMessage(), equalTo(CrankyCircuitBreakerService.ERROR_MESSAGE));
        }
    }

    public void testEmptyWithCranky() {
        try {
            testSimple(crankyDriverContext(), 0, between(10, 10_000));
            logger.info("cranky didn't break");
        } catch (CircuitBreakingException e) {
            logger.info("broken", e);
            assertThat(e.getMessage(), equalTo(CrankyCircuitBreakerService.ERROR_MESSAGE));
        }
    }

    public void testShardDataPartitioningWithCranky() {
        int size = between(1_000, 20_000);
        int limit = between(10, size);
        try {
            testSimple(crankyDriverContext(), size, limit);
            logger.info("cranky didn't break");
        } catch (CircuitBreakingException e) {
            logger.info("broken", e);
            assertThat(e.getMessage(), equalTo(CrankyCircuitBreakerService.ERROR_MESSAGE));
        }
    }

    private void testSimple(DriverContext ctx, int size, int limit) {
        LuceneSourceOperator.Factory factory = simple(DataPartitioning.SHARD, size, limit);
        Operator.OperatorFactory readS = ValuesSourceReaderOperatorTests.factory(reader, S_FIELD, ElementType.LONG);

        List<Page> results = new ArrayList<>();

        OperatorTestCase.runDriver(
            new Driver(ctx, factory.get(ctx), List.of(readS.get(ctx)), new TestResultPageSinkOperator(results::add), () -> {})
        );
        OperatorTestCase.assertDriverContext(ctx);

        for (Page page : results) {
            assertThat(page.getPositionCount(), lessThanOrEqualTo(factory.maxPageSize()));
        }

        for (Page page : results) {
            LongBlock sBlock = page.getBlock(1);
            for (int p = 0; p < page.getPositionCount(); p++) {
                assertThat(sBlock.getLong(sBlock.getFirstValueIndex(p)), both(greaterThanOrEqualTo(0L)).and(lessThan((long) size)));
            }
        }
        int maxPages = Math.min(size, limit);
        int minPages = (int) Math.ceil(maxPages / factory.maxPageSize());
        assertThat(results, hasSize(both(greaterThanOrEqualTo(minPages)).and(lessThanOrEqualTo(maxPages))));
    }

    /**
     * Creates a mock search context with the given index reader.
     * The returned mock search context can be used to test with {@link LuceneOperator}.
     */
    public static SearchContext mockSearchContext(IndexReader reader, int shardId) {
        try {
            ContextIndexSearcher searcher = new ContextIndexSearcher(
                reader,
                IndexSearcher.getDefaultSimilarity(),
                IndexSearcher.getDefaultQueryCache(),
                TrivialQueryCachingPolicy.NEVER,
                true
            );
            SearchContext searchContext = mock(SearchContext.class);
            when(searchContext.searcher()).thenReturn(searcher);
            SearchExecutionContext searchExecutionContext = mock(SearchExecutionContext.class);
            when(searchContext.getSearchExecutionContext()).thenReturn(searchExecutionContext);
            when(searchExecutionContext.getFullyQualifiedIndex()).thenReturn(new Index("test", "uid"));
            when(searchExecutionContext.getShardId()).thenReturn(shardId);
            return searchContext;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
