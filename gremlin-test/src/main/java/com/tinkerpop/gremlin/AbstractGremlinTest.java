package com.tinkerpop.gremlin;

import com.tinkerpop.gremlin.process.Traversal;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.strategy.GraphStrategy;
import org.apache.commons.configuration.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assume.assumeThat;

/**
 * Sets up g based on the current graph configuration and checks required features for the test.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public abstract class AbstractGremlinTest {
    protected Graph g;
    protected Configuration config;
    protected GraphStrategy strategyToTest;
    protected GraphProvider graphProvider;

    @Rule
    public TestName name = new TestName();

    public AbstractGremlinTest() {
        this(null);
    }

    public AbstractGremlinTest(final GraphStrategy strategyToTest) {
        this.strategyToTest = strategyToTest;
    }

    @Before
    public void setup() throws Exception {
        graphProvider = GraphManager.get();
        config = graphProvider.standardGraphConfiguration(this.getClass(), name.getMethodName());

        // this should clear state from a previously unfinished test. since the graph does not yet exist,
        // persisted graphs will likely just have their directories removed
        graphProvider.clear(config);

        // not sure how the strategy can ever be null, but it seems to happen in the performance tests
        g = graphProvider.openTestGraph(config, strategyToTest);

        final Method testMethod = this.getClass().getMethod(cleanMethodName(name.getMethodName()));

        final LoadGraphWith[] loadGraphWiths = testMethod.getAnnotationsByType(LoadGraphWith.class);

        // get feature requirements on the test method and add them to the list of ones to check
        final FeatureRequirement[] featureRequirement = testMethod.getAnnotationsByType(FeatureRequirement.class);
        final List<FeatureRequirement> frs = new ArrayList<>(Arrays.asList(featureRequirement));

        // if the graph is loading data then it will come with it's own requirements
        if (loadGraphWiths.length > 0) frs.addAll(loadGraphWiths[0].value().featuresRequired());

        // if the graph has a set of feature requirements bundled together then add those
        final FeatureRequirementSet[] featureRequirementSets = testMethod.getAnnotationsByType(FeatureRequirementSet.class);
        if (featureRequirementSets.length > 0)
            frs.addAll(Arrays.stream(featureRequirementSets)
                             .flatMap(f -> f.value().featuresRequired().stream()).collect(Collectors.toList()));

        final List<Graph.FeatureOverride> featureOverrides = Arrays.asList(g.getClass().getAnnotationsByType(Graph.FeatureOverride.class));

        // process the unique set of feature requirements
        final Set<FeatureRequirement> featureRequirementSet = new HashSet<>(frs);
        for (FeatureRequirement fr : featureRequirementSet) {
            try {
                //System.out.println(String.format("Assume that %s meets Feature Requirement - %s - with %s", fr.featureClass().getSimpleName(), fr.feature(), fr.supported()));
                final Optional<Graph.FeatureOverride> override = hasOverridenFeature(featureOverrides, fr);
                if (override.isPresent())
                    assumeThat(override.get().supported(), is(fr.supported()));
                else
                    assumeThat(g.features().supports(fr.featureClass(), fr.feature()), is(fr.supported()));
            } catch (NoSuchMethodException nsme) {
                throw new NoSuchMethodException(String.format("[supports%s] is not a valid feature on %s", fr.feature(), fr.featureClass()));
            }
        }

        // load a graph with sample data if the annotation is present on the test
        if (loadGraphWiths.length > 0) graphProvider.loadGraphData(g, loadGraphWiths[0]);

        prepareGraph(g);
    }

    private static Optional<Graph.FeatureOverride> hasOverridenFeature(final List<Graph.FeatureOverride> featureOverrides, final FeatureRequirement fr) {
        return featureOverrides.stream().filter(fo -> fo.featureClass().equals(fr.featureClass()) && fo.feature().equals(fr.feature())).findFirst();
    }

    protected void prepareGraph(final Graph g) throws Exception {
        // do nothing
    }

    @After
    public void tearDown() throws Exception {
        if (null != graphProvider) {
            graphProvider.clear(g, config);
            g = null;
            config = null;
            strategyToTest = null;
            graphProvider = null;
        }
    }

    /**
     * Looks up the identifier as generated by the current source graph being tested.
     *
     * @param vertexName a unique string that will identify a graph element within a graph
     * @return the id as generated by the graph
     */
    protected Object convertToVertexId(final String vertexName) {
        return convertToVertexId(g, vertexName);
    }

    /**
     * Looks up the identifier as generated by the current source graph being tested.
     *
     * @param g          the graph to get the element id from
     * @param vertexName a unique string that will identify a graph element within a graph
     * @return the id as generated by the graph
     */
    protected Object convertToVertexId(final Graph g, final String vertexName) {
        return convertToVertex(g, vertexName).id();
    }

    protected Vertex convertToVertex(final Graph g, final String vertexName) {
        // all test graphs have "name" as a unique id which makes it easy to hardcode this...works for now
        return ((Vertex) g.V().has("name", vertexName).next());
    }

    protected Object convertToEdgeId(final String outVertexName, String edgeLabel, final String inVertexName) {
        return convertToEdgeId(g, outVertexName, edgeLabel, inVertexName);
    }

    protected Object convertToEdgeId(final Graph g, final String outVertexName, String edgeLabel, final String inVertexName) {
        return ((Edge) g.V().has("name", outVertexName).outE(edgeLabel).as("e").inV().has("name", inVertexName).back("e").next()).id();
    }

    /**
     * Utility method that commits if the graph supports transactions.
     */
    protected void tryCommit(final Graph g) {
        if (g.features().graph().supportsTransactions())
            g.tx().commit();
    }

    /**
     * Utility method that commits if the graph supports transactions and executes an assertion function before and
     * after the commit.  It assumes that the assertion should be true before and after the commit.
     */
    protected void tryCommit(final Graph g, final Consumer<Graph> assertFunction) {
        assertFunction.accept(g);
        if (g.features().graph().supportsTransactions()) {
            g.tx().commit();
            assertFunction.accept(g);
        }
    }

    /**
     * Utility method that rollsback if the graph supports transactions.
     */
    protected void tryRollback(final Graph g) {
        if (g.features().graph().supportsTransactions())
            g.tx().rollback();
    }

    /**
     * If using "parameterized test" junit will append an identifier to the end of the method name which prevents it
     * from being found via reflection.  This method removes that suffix.
     */
    private static String cleanMethodName(final String methodName) {
        if (methodName.endsWith("]")) {
            return methodName.substring(0, methodName.indexOf("["));
        }

        return methodName;
    }

    protected void printTraversalForm(final Traversal traversal) {
        System.out.println("Testing: " + traversal);
        traversal.strategies().apply();
        System.out.println("         " + traversal);
    }
}
