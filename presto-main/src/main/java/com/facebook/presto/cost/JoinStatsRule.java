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
package com.facebook.presto.cost;

import com.facebook.presto.Session;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.iterative.Lookup;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.Expression;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.sql.ExpressionUtils.combineConjuncts;
import static com.facebook.presto.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static com.facebook.presto.sql.tree.ComparisonExpressionType.EQUAL;
import static com.google.common.collect.ImmutableList.toImmutableList;

public class JoinStatsRule
        implements ComposableStatsCalculator.Rule
{
    private final FilterStatsCalculator filterStatsCalculator;

    public JoinStatsRule(FilterStatsCalculator filterStatsCalculator)
    {
        this.filterStatsCalculator = filterStatsCalculator;
    }

    @Override
    public Optional<PlanNodeStatsEstimate> calculate(PlanNode node, Lookup lookup, Session session, Map<Symbol, Type> types)
    {
        if (!(node instanceof JoinNode)) {
            return Optional.empty();
        }
        JoinNode joinNode = (JoinNode) node;

        List<Expression> comparisons = joinNode.getCriteria().stream()
                .map(criteria -> new ComparisonExpression(EQUAL, criteria.getLeft().toSymbolReference(), criteria.getRight().toSymbolReference()))
                .collect(toImmutableList());

        PlanNodeStatsEstimate mergedInputCosts = crossJoinStats(joinNode, lookup, session, types);
        Expression predicate = combineConjuncts(combineConjuncts(comparisons), joinNode.getFilter().orElse(TRUE_LITERAL));
        PlanNodeStatsEstimate filteredStatistics = filterStatsCalculator.filterStats(mergedInputCosts, predicate, session, types);

        PlanNodeStatsEstimate.Builder finalStatsBuilder = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(filteredStatistics.getOutputRowCount());
        filteredStatistics.getSymbolsWithKnownStatistics().stream()
                .filter(node.getOutputSymbols()::contains)
                .forEach(symbol -> finalStatsBuilder.addSymbolStatistics(symbol, filteredStatistics.getSymbolStatistics(symbol)));

        return Optional.of(finalStatsBuilder.build());
    }

    private PlanNodeStatsEstimate crossJoinStats(JoinNode joinNode, Lookup lookup, Session session, Map<Symbol, Type> types)
    {
        PlanNodeStatsEstimate leftStats = lookup.getStats(joinNode.getLeft(), session, types);
        PlanNodeStatsEstimate rightStats = lookup.getStats(joinNode.getRight(), session, types);

        PlanNodeStatsEstimate.Builder builder = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(leftStats.getOutputRowCount() * rightStats.getOutputRowCount());

        joinNode.getLeft().getOutputSymbols().forEach(symbol -> builder.addSymbolStatistics(symbol, leftStats.getSymbolStatistics(symbol)));
        joinNode.getRight().getOutputSymbols().forEach(symbol -> builder.addSymbolStatistics(symbol, rightStats.getSymbolStatistics(symbol)));

        return builder.build();
    }
}