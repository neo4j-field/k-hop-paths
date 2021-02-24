package org.neo4j.field;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Path;
import org.neo4j.harness.junit.extension.Neo4jExtension;
import org.neo4j.internal.helpers.collection.Iterators;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class KHopProcsTest {

    @RegisterExtension
    static Neo4jExtension neo4j = Neo4jExtension.builder()
            .withDisabledServer()
            .withProcedure(KHopProcs.class)
            .withFunction(KHopProcs.class)
            .withFixture("create (:Person{id:'p1'})-[:WORK_FOR{position:'bigboss'}]->(c:Company{id:'c1'})<-[:INVEST_TO{pct:7.8}]-(:Person{id:'p2'})")
            .build();

    @Test
    void simple(GraphDatabaseService db) {
        db.executeTransactionally("match (p1:Person{id:'p1'}),(p2:Person{id:'p2'}) CALL neo4j.khop(p1, p2, 2, 5.0, ['bigboss']) yield path return path",
                Collections.emptyMap(), result -> {
            Map<String, Object> map = Iterators.single(result);
            Object path = map.get("path");
            Assertions.assertTrue(path instanceof Path);
            return null;
        });

    }

    @Test
    void testParallel(GraphDatabaseService db) {
        /*
        (a)--(b)--(c)--(f)
          \       /    /
           \ -- (d)--(e)
         */

        db.executeTransactionally("create (a:Node{id:'a'})-[:r]->(b:Node{id:'b'})-[:r]->(c:Node{id:'c'})-[:r]->(f:Node{id:'f'})," +
                "(a)-[:r]->(d:Node{id:'d'})-[:r]->(c), (d)-[:r]->(e:Node{id:'e'})-[:r]->(f)");

        long count = db.executeTransactionally("match (a:Node{id:'a'}), (f:Node{id:'f'}) return neo4j.khop.parallel(a, f, 3) as count",
                Collections.emptyMap(), result -> (long)Iterators.single(result).get("count"));
        assertEquals(count, 3);
    }

}
