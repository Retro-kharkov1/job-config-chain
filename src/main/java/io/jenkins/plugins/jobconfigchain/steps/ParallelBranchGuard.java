package io.jenkins.plugins.jobconfigchain.steps;

import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

/**
 * Detects whether a {@link FlowNode} is enclosed inside a {@code parallel {}} branch body (see
 * pipeline-steps.md's "Forbidden inside parallel {}" rule),
 * by walking its enclosing blocks looking for the marker a {@code parallel} branch's own start node
 * carries. Grounded against {@code jenkinsci/workflow-api-plugin}'s own {@code docs/flowgraph.md} and
 * {@code jenkinsci/pipeline-plugin} PR #80 (JENKINS-26122): each parallel branch's start node is
 * decorated with a {@link ThreadNameAction} (via {@code workflow-cps}'s internal, non-public
 * {@code ParallelLabelAction}, which this code deliberately never references directly — it depends
 * only on the stable {@code workflow-api} surface, not on the CPS execution engine's internals; see
 * {@code docs/development/tech-lead-pipeline-resolution-controls-review-2026-09-03.md} §7 for the
 * full grounding and the rejected {@code CpsThread}/{@code workflow-cps} alternative).
 */
final class ParallelBranchGuard {

    private ParallelBranchGuard() {
    }

    /**
     * @return the branch's thread name if {@code flowNode} is enclosed in a parallel branch, else
     *         {@code null}. A {@code null} input (context not yet available, or step invoked in a
     *         context with no flow graph at all) is treated as "not inside a parallel branch" — fail
     *         open on this specific check only because the underlying condition (running inside a
     *         real Pipeline {@code parallel {}}) structurally cannot exist without a FlowNode also
     *         existing; a null FlowNode here means something else is unusual enough that this guard
     *         is not the right place to surface it.
     */
    static String enclosingParallelBranchName(FlowNode flowNode) {
        if (flowNode == null) {
            return null;
        }
        for (BlockStartNode enclosing : flowNode.iterateEnclosingBlocks()) {
            ThreadNameAction threadName = enclosing.getAction(ThreadNameAction.class);
            if (threadName != null) {
                return threadName.getThreadName();
            }
        }
        return null;
    }
}
