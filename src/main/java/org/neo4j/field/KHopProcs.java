package org.neo4j.field;

import org.neo4j.graphalgo.BasicEvaluationContext;
import org.neo4j.graphalgo.GraphAlgoFactory;
import org.neo4j.graphalgo.PathFinder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PathExpanderBuilder;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.List;
import java.util.stream.Stream;

public class KHopProcs {

    @Context
    public Transaction tx;

    @Context
    public GraphDatabaseService db;

    @Procedure(name="neo4j.khop", mode= Mode.READ)
    public Stream<PathResult> khop(@Name("node1") Node node1, @Name("node2") Node node2, @Name("depth") long depth,
                                   @Name("work_for treshold") Double workforTreshold, @Name("valid positions") List<String> validPositions) {
        PathFinder<Path> pathFinder = GraphAlgoFactory.pathsWithLength(
                new BasicEvaluationContext(tx, db),
                PathExpanderBuilder.empty()
                .add(RelationshipTypes.WORK_FOR)  // preselect valid rel types for performance
                .add(RelationshipTypes.INVEST_TO)
                .addRelationshipFilter(relationship ->
                        // we accept either invest_in with pct > treshold
                        // or work_for with a position being in validPositions
                        {
                            boolean result = (relationship.isType(RelationshipTypes.INVEST_TO) && (((Double) relationship.getProperty("pct", 0.0d)) > workforTreshold)) ||
                                    (relationship.isType(RelationshipTypes.WORK_FOR) && relationship.hasProperty("position") && validPositions.contains(relationship.getProperty("position")));
//                            System.out.println("evaluating " + relationship + " : " + result);
                            return result;
                        }
                ).build(),
                (int) depth
        );
        return Iterables.stream(pathFinder.findAllPaths(node1, node2)).map(PathResult::new);
    }

}
