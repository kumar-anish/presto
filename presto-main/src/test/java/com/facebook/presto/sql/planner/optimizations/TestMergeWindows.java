/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.spi.block.SortOrder;
import com.facebook.presto.sql.analyzer.FeaturesConfig;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.assertions.PlanAssert;
import com.facebook.presto.sql.planner.assertions.PlanMatchPattern;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.FrameBound;
import com.facebook.presto.sql.tree.SymbolReference;
import com.facebook.presto.sql.tree.WindowFrame;
import com.facebook.presto.testing.LocalQueryRunner;
import com.facebook.presto.tpch.TpchConnectorFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.any;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.anyNot;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.anySymbolReference;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.anyTree;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.functionCall;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.join;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.project;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.symbolReferenceStem;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.symbolStem;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.window;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;

public class TestMergeWindows
{
    private final LocalQueryRunner queryRunner;

    private final Symbol suppkey;
    private final Symbol orderkey;
    private final Symbol shipdate;
    private final Symbol quantity;

    private final SymbolReference quantityReference;
    private final SymbolReference discountReference;
    private final SymbolReference extendedpriceReference;

    private final Optional<WindowFrame> commonFrame;
    private final Optional<WindowFrame> defaultFrame;

    private final WindowNode.Specification specificationA;
    private final WindowNode.Specification specificationB;

    public TestMergeWindows()
    {
        queryRunner = new LocalQueryRunner(testSessionBuilder()
                .setCatalog("local")
                .setSchema("tiny")
                .build());

        queryRunner.createCatalog(queryRunner.getDefaultSession().getCatalog().get(),
                new TpchConnectorFactory(1),
                ImmutableMap.<String, String>of());

        commonFrame = Optional.of(new WindowFrame(
                WindowFrame.Type.ROWS,
                new FrameBound(FrameBound.Type.UNBOUNDED_PRECEDING),
                Optional.of(new FrameBound(FrameBound.Type.CURRENT_ROW))));

        defaultFrame = Optional.empty();

        suppkey = new Symbol("suppkey");
        orderkey = new Symbol("orderkey");
        shipdate = new Symbol("shipdate");
        quantity = new Symbol("quantity");

        quantityReference = new SymbolReference("quantity");
        discountReference = new SymbolReference("discount");
        extendedpriceReference = new SymbolReference("extendedprice");

        specificationA = new WindowNode.Specification(
                ImmutableList.of(suppkey),
                ImmutableList.of(orderkey),
                ImmutableMap.of(orderkey, SortOrder.ASC_NULLS_LAST));

        specificationB = new WindowNode.Specification(
                ImmutableList.of(orderkey),
                ImmutableList.of(shipdate),
                ImmutableMap.of(shipdate, SortOrder.ASC_NULLS_LAST));
    }

    /**
     * There are two types of tests in here, and they answer two different
     * questions about MergeWindows (MW):
     *
     * 1) Is MW working as it's supposed to be? The tests running the minimal
     * set of optimizers can tell us this.
     * 2) Has some other optimizer changed the plan in such a way that MW no
     * longer merges windows with identical specifications because the plan
     * that MW sees cannot be optimized by MW? The test running the full set
     * of optimizers answers this, though it isn't actually meaningful unless
     * we know the answer to question 1 is "yes".
     *
     * The tests that use only the minimal set of optimizers are closer to true
     * "unit" tests in that they verify the behavior of MW with as few
     * external dependencies as possible. Those dependencies to include the
     * parser and analyzer, so the phrase "unit" tests should be taken with a
     * grain of salt. Using the parser and anayzler instead of creating plan
     * nodes by hand does have a couple of advantages over a true unit test:
     * 1) The tests are more self-maintaining.
     * 2) They're a lot easier to read.
     * 3) It's a lot less typing.
     *
     * The test that runs with all of the optimzers acts as an integration test
     * and ensures that MW is effective when run with the complete set of
     * optimizers.
     */
    @Test
    public void testMergeableWindowsAllOptimizers()
    {
        @Language("SQL") String sql = "SELECT " +
                "SUM(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_A, " +
                "SUM(quantity) OVER (PARTITION BY orderkey ORDER BY shipdate ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_B, " +
                "SUM(discount) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_discount_A " +
                "FROM lineitem";

        PlanMatchPattern pattern =
                anyTree(
                        window(specificationA,
                                ImmutableList.of(
                                functionCall("sum", commonFrame, quantityReference),
                                functionCall("sum", commonFrame, discountReference)),
                                anyTree(
                                        window(specificationB,
                                                ImmutableList.of(
                                                functionCall("sum", commonFrame, quantityReference)),
                                                anyNot(WindowNode.class,
                                                        anyTree())))));

        Plan actualPlan = queryRunner.inTransaction(transactionSession -> queryRunner.createPlan(transactionSession, sql));
        queryRunner.inTransaction(transactionSession -> {
            PlanAssert.assertPlan(transactionSession, queryRunner.getMetadata(), actualPlan, pattern);
            return null;
        });
    }

    @Test
    public void testIdenticalWindowSpecificationsABA()
    {
        @Language("SQL") String sql = "SELECT " +
                "SUM(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_A, " +
                "SUM(quantity) OVER (PARTITION BY orderkey ORDER BY shipdate ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_B, " +
                "SUM(discount) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_discount_A " +
                "FROM lineitem";

        assertUnitPlan(sql,
                anyTree(
                        window(specificationB,
                                ImmutableList.of(
                                functionCall("sum", commonFrame, quantityReference)),
                                window(specificationA,
                                        ImmutableList.of(
                                        functionCall("sum", commonFrame, quantityReference),
                                        functionCall("sum", commonFrame, discountReference)),
                                        anyNot(WindowNode.class)))));
    }

    @Test
    public void testIdenticalWindowSpecificationsABcpA()
    {
        @Language("SQL") String sql = "SELECT " +
                "SUM(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_A, " +
                "LAG(quantity, 1, 0.0) OVER (PARTITION BY orderkey ORDER BY shipdate ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_B, " +
                "SUM(discount) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_discount_A " +
                "FROM lineitem";

        assertUnitPlan(sql,
                anyTree(
                        window(specificationA,
                                ImmutableList.of(functionCall("sum", commonFrame, discountReference)),
                                window(specificationB,
                                        ImmutableList.of(functionCall("lag", commonFrame, quantityReference, anySymbolReference(), anySymbolReference())),
                                        project(
                                                window(specificationA,
                                                        ImmutableList.of(
                                                        functionCall("sum", commonFrame, quantityReference)),
                                                        any()))))));
    }

    @Test
    public void testIdenticalWindowSpecificationsAAcpA()
    {
        @Language("SQL") String sql = "SELECT " +
                "SUM(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_A, " +
                "LAG(quantity, 1, 0.0) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_B, " +
                "SUM(discount) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_discount_A " +
                "FROM lineitem";

        assertUnitPlan(sql,
                anyTree(
                        window(specificationA,
                                ImmutableList.of(
                                functionCall("sum", commonFrame, discountReference),
                                functionCall("lag", commonFrame, quantityReference, anySymbolReference(), anySymbolReference())),
                                project(
                                        window(specificationA,
                                                ImmutableList.of(
                                                functionCall("sum", commonFrame, quantityReference)),
                                                any())))));
    }

    @Test
    public void testIdenticalWindowSpecificationsDefaultFrame()
    {
        WindowNode.Specification specificationC = new WindowNode.Specification(
                ImmutableList.of(suppkey),
                ImmutableList.of(orderkey),
                ImmutableMap.of(orderkey, SortOrder.ASC_NULLS_LAST));

        WindowNode.Specification specificationD = new WindowNode.Specification(
                ImmutableList.of(orderkey),
                ImmutableList.of(shipdate),
                ImmutableMap.of(shipdate, SortOrder.ASC_NULLS_LAST));

        @Language("SQL") String sql = "SELECT " +
                "SUM(quantity) OVER (PARTITION By suppkey ORDER BY orderkey), " +
                "SUM(quantity) OVER (PARTITION BY orderkey ORDER BY shipdate), " +
                "SUM(discount) OVER (PARTITION BY suppkey ORDER BY orderkey) " +
                "FROM lineitem";

        assertUnitPlan(sql,
                anyTree(
                        window(specificationD,
                                ImmutableList.of(
                                functionCall("sum", defaultFrame, quantityReference)),
                                window(specificationC,
                                        ImmutableList.of(
                                        functionCall("sum", defaultFrame, quantityReference),
                                        functionCall("sum", defaultFrame, discountReference)),
                                        anyNot(WindowNode.class)))));
    }

    @Test
    public void testMergeDifferentFrames()
    {
        Optional<WindowFrame> frameC = Optional.of(new WindowFrame(
                WindowFrame.Type.ROWS,
                new FrameBound(FrameBound.Type.UNBOUNDED_PRECEDING),
                Optional.of(new FrameBound(FrameBound.Type.CURRENT_ROW))));

        WindowNode.Specification specificationC = new WindowNode.Specification(
                ImmutableList.of(suppkey),
                ImmutableList.of(orderkey),
                ImmutableMap.of(orderkey, SortOrder.ASC_NULLS_LAST));

        Optional<WindowFrame> frameD = Optional.of(new WindowFrame(
                WindowFrame.Type.ROWS,
                new FrameBound(FrameBound.Type.CURRENT_ROW),
                Optional.of(new FrameBound(FrameBound.Type.UNBOUNDED_FOLLOWING))));

        @Language("SQL") String sql = "SELECT " +
                "SUM(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_C, " +
                "AVG(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) avg_quantity_D, " +
                "SUM(discount) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_discount_C " +
                "FROM lineitem";

        assertUnitPlan(sql,
                anyTree(
                        window(specificationC,
                                ImmutableList.of(
                                functionCall("avg", frameD, quantityReference),
                                functionCall("sum", frameC, discountReference),
                                functionCall("sum", frameC, quantityReference)),
                                any())));
    }

    @Test
    public void testMergeDifferentFramesWithDefault()
    {
        Optional<WindowFrame> frameD = Optional.of(new WindowFrame(
                WindowFrame.Type.ROWS,
                new FrameBound(FrameBound.Type.CURRENT_ROW),
                Optional.of(new FrameBound(FrameBound.Type.UNBOUNDED_FOLLOWING))));

        WindowNode.Specification specificationD = new WindowNode.Specification(
                ImmutableList.of(suppkey),
                ImmutableList.of(orderkey),
                ImmutableMap.of(orderkey, SortOrder.ASC_NULLS_LAST));

        @Language("SQL") String sql = "SELECT " +
                "SUM(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey) sum_quantity_C, " +
                "AVG(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) avg_quantity_D, " +
                "SUM(discount) OVER (PARTITION BY suppkey ORDER BY orderkey) sum_discount_C " +
                "FROM lineitem";

        assertUnitPlan(sql,
                anyTree(
                        window(specificationD,
                                ImmutableList.of(
                                functionCall("avg", frameD, quantityReference),
                                functionCall("sum", defaultFrame, discountReference),
                                functionCall("sum", defaultFrame, quantityReference)),
                                any())));
    }

    @Test
    public void testNotMergeAcrossJoinBranches()
    {
        @Language("SQL") String sql = "WITH foo AS (" +
                "SELECT " +
                "suppkey, orderkey, partkey, " +
                "SUM(discount) OVER (PARTITION BY orderkey ORDER BY shipdate, quantity DESC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) a " +
                "FROM lineitem WHERE (partkey = 272 OR partkey = 273) AND suppkey > 50 " +
                "), " +
                "bar AS ( " +
                "SELECT " +
                "suppkey, orderkey, partkey, " +
                "AVG(quantity) OVER (PARTITION BY orderkey ORDER BY shipdate, quantity DESC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) b " +
                "FROM lineitem WHERE (partkey = 272 OR partkey = 273) AND suppkey > 50 " +
                ")" +
                "SELECT * FROM foo, bar WHERE foo.a = bar.b";

        Symbol orderkeyC = symbolStem("orderkey");
        Symbol quantityC = symbolStem("quantity");
        Symbol shipdateC = symbolStem("shipdate");

        WindowNode.Specification specificationC = new WindowNode.Specification(
                ImmutableList.of(orderkeyC),
                ImmutableList.of(shipdateC, quantityC),
                ImmutableMap.of(shipdateC, SortOrder.ASC_NULLS_LAST, quantityC, SortOrder.DESC_NULLS_LAST));

        assertUnitPlan(sql,
                anyTree(
                        join(JoinNode.Type.INNER, ImmutableList.of(),
                                any(
                                        window(specificationC, ImmutableList.of(functionCall("sum", commonFrame, symbolReferenceStem("discount"))),
                                                anyTree())),
                                any(
                                        window(specificationC, ImmutableList.of(functionCall("avg", commonFrame, symbolReferenceStem("quantity"))),
                                                anyTree())))));
    }

    @Test
    public void testNotMergeDifferentPartition()
    {
        @Language("SQL") String sql = "SELECT " +
                "SUM(extendedprice) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_extendedprice_A, " +
                "SUM(quantity) over (PARTITION BY quantity ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_C " +
                "FROM lineitem";

        WindowNode.Specification specificationC = new WindowNode.Specification(
                ImmutableList.of(quantity),
                ImmutableList.of(orderkey),
                ImmutableMap.of(orderkey, SortOrder.ASC_NULLS_LAST));

        assertUnitPlan(sql,
                anyTree(
                        window(specificationC, ImmutableList.of(
                                functionCall("sum", commonFrame, quantityReference)),
                                window(specificationA, ImmutableList.of(
                                        functionCall("sum", commonFrame, extendedpriceReference)),
                                        anyNot(WindowNode.class)))));
    }

    @Test
    public void testNotMergeDifferentOrderBy()
    {
        @Language("SQL") String sql = "SELECT " +
                "SUM(extendedprice) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_extendedprice_A, " +
                "SUM(quantity) OVER (PARTITION BY suppkey ORDER BY quantity ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_C " +
                "FROM lineitem";

        WindowNode.Specification specificationC = new WindowNode.Specification(
                ImmutableList.of(suppkey),
                ImmutableList.of(quantity),
                ImmutableMap.of(quantity, SortOrder.ASC_NULLS_LAST));

        assertUnitPlan(sql,
                anyTree(
                        window(specificationC, ImmutableList.of(
                                functionCall("sum", commonFrame, quantityReference)),
                                window(specificationA, ImmutableList.of(
                                        functionCall("sum", commonFrame, extendedpriceReference)),
                                        anyNot(WindowNode.class)))));
    }

    @Test
    public void testNotMergeDifferentOrdering()
    {
        @Language("SQL") String sql = "SELECT " +
                "SUM(extendedprice) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_extendedprice_A, " +
                "SUM(quantity) over (PARTITION BY suppkey ORDER BY orderkey DESC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_C, " +
                "SUM(discount) over (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_discount_A " +
                "FROM lineitem";

        WindowNode.Specification specificationC = new WindowNode.Specification(
                ImmutableList.of(suppkey),
                ImmutableList.of(orderkey),
                ImmutableMap.of(orderkey, SortOrder.DESC_NULLS_LAST));

        assertUnitPlan(sql,
                anyTree(
                        window(specificationC, ImmutableList.of(
                                functionCall("sum", commonFrame, quantityReference)),
                                window(specificationA, ImmutableList.of(
                                        functionCall("sum", commonFrame, extendedpriceReference),
                                        functionCall("sum", commonFrame, discountReference)),
                                        anyNot(WindowNode.class)))));
    }

    @Test
    public void testNotMergeDifferentNullOrdering()
    {
        @Language("SQL") String sql = "SELECT " +
                "SUM(extendedprice) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_extendedprice_A, " +
                "SUM(quantity) OVER (PARTITION BY suppkey ORDER BY orderkey NULLS FIRST ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_quantity_C, " +
                "SUM(discount) OVER (PARTITION BY suppkey ORDER BY orderkey ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) sum_discount_A " +
                "FROM lineitem";

        WindowNode.Specification specificationC = new WindowNode.Specification(
                ImmutableList.of(suppkey),
                ImmutableList.of(orderkey),
                ImmutableMap.of(orderkey, SortOrder.ASC_NULLS_FIRST));

        assertUnitPlan(sql,
                anyTree(
                        window(specificationC, ImmutableList.of(
                                functionCall("sum", commonFrame, quantityReference)),
                                window(specificationA, ImmutableList.of(
                                        functionCall("sum", commonFrame, extendedpriceReference),
                                        functionCall("sum", commonFrame, discountReference)),
                                        anyNot(WindowNode.class)))));
    }

    private void assertUnitPlan(@Language("SQL") String sql, PlanMatchPattern pattern)
    {
        queryRunner.inTransaction(transactionSession -> {
            Plan actualPlan = unitPlan(transactionSession, sql);
            PlanAssert.assertPlan(transactionSession, queryRunner.getMetadata(), actualPlan, pattern);
            return null;
        });
    }

    private Plan unitPlan(Session transactionSession, @Language("SQL") String sql)
    {
        FeaturesConfig featuresConfig = new FeaturesConfig()
                .setExperimentalSyntaxEnabled(true)
                .setDistributedIndexJoinsEnabled(false)
                .setOptimizeHashGeneration(true);
        List<PlanOptimizer> optimizers = ImmutableList.of(
                        new UnaliasSymbolReferences(),
                        new PruneIdentityProjections(),
                        new MergeWindows(),
                        new PruneUnreferencedOutputs());
        return queryRunner.createPlan(transactionSession, sql, featuresConfig, optimizers);
    }
}
