package org.neo4j.field;

import org.neo4j.graphalgo.BasicEvaluationContext;
import org.neo4j.graphalgo.GraphAlgoFactory;
import org.neo4j.graphalgo.PathFinder;
import org.neo4j.graphalgo.impl.util.PathImpl;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PathExpander;
import org.neo4j.graphdb.PathExpanderBuilder;
import org.neo4j.graphdb.PathExpanders;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.Paths;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.UserFunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class KHopProcs {

    @Context
    public Transaction tx;

    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;

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

    @UserFunction(name="neo4j.khop.parallel")
    public long khopParallelCount(@Name("node1") Node node1, @Name("node2") Node node2, @Name("depth") long depth/*,
                                   @Name("work_for treshold") Double workforTreshold, @Name("valid positions") List<String> validPositions*/) {

        AtomicLong result = new AtomicLong(0);
        ExecutorService executorService = Executors.newWorkStealingPool();

        PathExpander<Object> pathExpander = PathExpanders.allTypesAndDirections();
                /*PathExpanderBuilder.empty()
                .add(RelationshipTypes.WORK_FOR)  // preselect valid rel types for performance
                .add(RelationshipTypes.INVEST_TO)
                .addRelationshipFilter(relationship ->
                                // we accept either invest_in with pct > treshold
                                // or work_for with a position being in validPositions
                        {
                            boolean r = (relationship.isType(RelationshipTypes.INVEST_TO) && (((Double) relationship.getProperty("pct", 0.0d)) > workforTreshold)) ||
                                    (relationship.isType(RelationshipTypes.WORK_FOR) && relationship.hasProperty("position") && validPositions.contains(relationship.getProperty("position")));
//                            System.out.println("evaluating " + relationship + " : " + result);
                            return r;
                        }
                ).build();*/
        try {
            executorService.submit(new LeftRightPath(
                    new long[]{node1.getId()}, new long[0], new long[]{node2.getId()}, new long[0],
                    pathExpander, pathExpander.reverse(),
                    executorService,
                    depth,
                    result
            )).get();
//            Thread.sleep(10000);
            executorService.shutdown();
            if (!executorService.awaitTermination(1, TimeUnit.HOURS)) {
                log.error("timout reached");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        return result.get();
    }

    public class LeftRightPath implements Runnable {

        private final long[] leftNodeIds;
        private final long[] leftRelIds;
        private final long[] rightNodeIds;
        private final long[] rightRelIds;
        private final PathExpander<Object> leftExpander;
        private final PathExpander<Object> rightExpander;
        private final ExecutorService executorService;
        private final long depth;
        private final AtomicLong result;

        public LeftRightPath(
                long[] leftNodeIds, long[] leftRelIds, long[] rightNodeIds, long[] rightRelIds,
                PathExpander<Object> leftExpander,
                PathExpander<Object> rightExpander,
                ExecutorService executorService,
                long depth, AtomicLong result
        ) {
            this.leftNodeIds = leftNodeIds;
            this.leftRelIds = leftRelIds;
            this.rightNodeIds = rightNodeIds;
            this.rightRelIds = rightRelIds;
            this.leftExpander = leftExpander;
            this.rightExpander = rightExpander;
            this.executorService = executorService;
            this.depth = depth;
            this.result = result;
        }

        @Override
        public void run() {
            try (Transaction localTx = db.beginTx()) {
                System.out.println("left " + Arrays.toString(leftNodeIds)+ ", right:  "+ Arrays.toString(rightNodeIds));
                if (log.isDebugEnabled()) {
                    log.debug("left %s, right %s", Arrays.toString(leftNodeIds), Arrays.toString(rightNodeIds));
                }

                long leftNodeId = leftNodeIds[leftNodeIds.length - 1];
                Node left = localTx.getNodeById(leftNodeId);

                // TODO: consider direction when calculating degree
                long leftDegree = Arrays.stream(RelationshipTypes.values()).mapToLong(value -> left.getDegree(value)).sum();
                long rightNodeId = rightNodeIds[rightNodeIds.length - 1];
                Node right = localTx.getNodeById(rightNodeId);
                long rightDegree = Arrays.stream(RelationshipTypes.values()).mapToLong(value -> right.getDegree(value)).sum();

                // we do expand on the side having a smaller degree. In case degrees are the same, we expand on the shorter side
                boolean expandFromLeft = leftDegree==rightDegree ?
                        leftRelIds.length < rightRelIds.length
                        : leftDegree < rightDegree;

                if (expandFromLeft) {
                    expand(left, leftExpander, rightNodeId, localTx, (traversedNodeId, traversedRelationshipId) ->
                            new LeftRightPath(
                                    appendArray(leftNodeIds, traversedNodeId),
                                    appendArray(leftRelIds, traversedRelationshipId),
                                    rightNodeIds,
                                    rightRelIds,
                                    leftExpander, rightExpander, executorService, depth, result));
                } else {
                    expand(right, rightExpander, leftNodeId, localTx, (traversedNodeId, traversedRelationshipId) ->
                            new LeftRightPath(
                                    leftNodeIds,
                                    leftRelIds,
                                    appendArray(rightNodeIds, traversedNodeId),
                                    appendArray(rightRelIds, traversedRelationshipId),
                                    leftExpander, rightExpander, executorService, depth, result));
                }
                localTx.commit();
            }
        }

        private void expand(Node nodeToExpand, PathExpander<Object> expander, long otherSideEndNodeId, Transaction localTx, BiFunction<Long, Long, Runnable> action) {
            Iterable<Relationship> rels = expander.expand(Paths.singleNodePath(nodeToExpand), null);
            for (Relationship r: rels) {
                long otherId = r.getOtherNodeId(nodeToExpand.getId());
                if (otherId == otherSideEndNodeId) {
                    logPath(localTx, r);
                    result.incrementAndGet();
                } else {
                    if ((getLength() < depth) && (!isRelationshipUsed(r.getId()))) {
                        executorService.submit(action.apply(otherId, r.getId()));
                    }
                }
            }
        }

        private void logPath(Transaction localTx, Relationship currentRelationship) {
            try {
//                if (log.isDebugEnabled()) {
                    PathImpl.Builder builder = new PathImpl.Builder(localTx.getNodeById(leftNodeIds[0]));

                    for (int i = 0; i < leftRelIds.length; i++) {
                        builder = builder.push(localTx.getRelationshipById(leftRelIds[i]));

                    }
                    builder = builder.push(currentRelationship);
                    for (int i = rightRelIds.length - 1; i >= 0; i--) {
                        builder = builder.push(localTx.getRelationshipById(rightRelIds[i]));
                    }
                    Path path = builder.build();
                    log.debug("found a path %s", path.toString());
                System.out.println("found a path " + path.toString());
//                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private boolean isRelationshipUsed(long thisId) {
            for (long id: leftRelIds) {
                if (id==thisId) return true;
            }
            for (long id: rightRelIds) {
                if (id==thisId) return true;
            }
            return false;
        }

        private int getLength() {
            return leftRelIds.length + rightRelIds.length;
        }

        private long[] appendArray(long[] old, long newElement) {
            long[] result = new long[old.length + 1];
            System.arraycopy(old, 0, result, 0, old.length);
            result[result.length - 1] = newElement;
            return result;
        }

    }

}
