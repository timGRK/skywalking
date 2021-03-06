/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
 *
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
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.agent.core.context;

import java.util.LinkedList;
import java.util.List;
import org.skywalking.apm.agent.core.boot.ServiceManager;
import org.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.skywalking.apm.agent.core.context.trace.EntrySpan;
import org.skywalking.apm.agent.core.context.trace.ExitSpan;
import org.skywalking.apm.agent.core.context.trace.LocalSpan;
import org.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.skywalking.apm.agent.core.context.trace.TraceSegmentRef;
import org.skywalking.apm.agent.core.dictionary.DictionaryManager;
import org.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.skywalking.apm.agent.core.dictionary.PossibleFound;
import org.skywalking.apm.agent.core.sampling.SamplingService;

/**
 * The <code>TracingContext</code> represents a core tracing logic controller.
 * It build the final {@link TracingContext}, by the stack mechanism,
 * which is similar with the codes work.
 *
 * In opentracing concept, it means, all spans in a segment tracing context(thread)
 * are CHILD_OF relationship, but no FOLLOW_OF.
 *
 * In skywalking core concept, FOLLOW_OF is an abstract concept
 * when cross-process MQ or cross-thread async/batch tasks happen,
 * we used {@link TraceSegmentRef} for these scenarios.
 * Check {@link TraceSegmentRef} which is from {@link ContextCarrier} or {@link ContextSnapshot}.
 *
 * @author wusheng
 */
public class TracingContext implements AbstractTracerContext {
    /**
     * @see {@link SamplingService}
     */
    private SamplingService samplingService;

    /**
     * The final {@link TraceSegment}, which includes all finished spans.
     */
    private TraceSegment segment;

    /**
     * Active spans stored in a Stack, usually called 'ActiveSpanStack'.
     * This {@link LinkedList} is the in-memory storage-structure.
     * <p>
     * I use {@link LinkedList#removeLast()}, {@link LinkedList#addLast(Object)} and {@link LinkedList#last} instead of
     * {@link #pop()}, {@link #push(AbstractTracingSpan)}, {@link #peek()}
     */
    private LinkedList<AbstractTracingSpan> activeSpanStack = new LinkedList<AbstractTracingSpan>();

    /**
     * A counter for the next span.
     */
    private int spanIdGenerator;

    /**
     * Initialize all fields with default value.
     */
    TracingContext() {
        this.segment = new TraceSegment();
        this.spanIdGenerator = 0;
        if (samplingService == null) {
            samplingService = ServiceManager.INSTANCE.findService(SamplingService.class);
        }
    }

    /**
     * Inject the context into the given carrier, only when the active span is an exit one.
     *
     * @param carrier to carry the context for crossing process.
     * @throws IllegalStateException, if the active span isn't an exit one.
     * @see {@link AbstractTracerContext#inject(ContextCarrier)}
     */
    @Override
    public void inject(ContextCarrier carrier) {
        AbstractTracingSpan span = this.activeSpan();
        if (!span.isExit()) {
            throw new IllegalStateException("Inject can be done only in Exit Span");
        }
        ExitSpan exitSpan = (ExitSpan)span;

        carrier.setTraceSegmentId(this.segment.getTraceSegmentId());
        carrier.setSpanId(span.getSpanId());

        carrier.setParentApplicationInstanceId(segment.getApplicationInstanceId());

        if (DictionaryUtil.isNull(exitSpan.getPeerId())) {
            carrier.setPeerHost(exitSpan.getPeer());
        } else {
            carrier.setPeerId(exitSpan.getPeerId());
        }
        List<TraceSegmentRef> refs = this.segment.getRefs();
        int operationId;
        String operationName;
        int entryApplicationInstanceId;
        if (refs != null && refs.size() > 0) {
            TraceSegmentRef ref = refs.get(0);
            operationId = ref.getEntryOperationId();
            operationName = ref.getEntryOperationName();
            entryApplicationInstanceId = ref.getEntryApplicationInstanceId();
        } else {
            AbstractTracingSpan firstSpan = first();
            operationId = firstSpan.getOperationId();
            operationName = firstSpan.getOperationName();
            entryApplicationInstanceId = this.segment.getApplicationInstanceId();
        }
        carrier.setEntryApplicationInstanceId(entryApplicationInstanceId);

        if (operationId == DictionaryUtil.nullValue()) {
            carrier.setEntryOperationName(operationName);
        } else {
            carrier.setEntryOperationId(operationId);
        }

        int parentOperationId = first().getOperationId();
        if (parentOperationId == DictionaryUtil.nullValue()) {
            carrier.setParentOperationName(first().getOperationName());
        } else {
            carrier.setParentOperationId(parentOperationId);
        }

        carrier.setDistributedTraceIds(this.segment.getRelatedGlobalTraces());
    }

    /**
     * Extract the carrier to build the reference for the pre segment.
     *
     * @param carrier carried the context from a cross-process segment.
     * @see {@link AbstractTracerContext#extract(ContextCarrier)}
     */
    @Override
    public void extract(ContextCarrier carrier) {
        this.segment.ref(new TraceSegmentRef(carrier));
        this.segment.relatedGlobalTraces(carrier.getDistributedTraceId());
    }

    /**
     * Capture the snapshot of current context.
     *
     * @return the snapshot of context for cross-thread propagation
     * @see {@link AbstractTracerContext#capture()}
     */
    @Override
    public ContextSnapshot capture() {
        List<TraceSegmentRef> refs = this.segment.getRefs();
        ContextSnapshot snapshot = new ContextSnapshot(segment.getTraceSegmentId(),
            activeSpan().getSpanId(),
            segment.getRelatedGlobalTraces());
        int entryOperationId;
        String entryOperationName;
        int entryApplicationInstanceId;
        AbstractTracingSpan firstSpan = first();
        if (refs != null && refs.size() > 0) {
            TraceSegmentRef ref = refs.get(0);
            entryOperationId = ref.getEntryOperationId();
            entryOperationName = ref.getEntryOperationName();
            entryApplicationInstanceId = ref.getEntryApplicationInstanceId();
        } else {
            entryOperationId = firstSpan.getOperationId();
            entryOperationName = firstSpan.getOperationName();
            entryApplicationInstanceId = this.segment.getApplicationInstanceId();
        }
        snapshot.setEntryApplicationInstanceId(entryApplicationInstanceId);

        if (entryOperationId == DictionaryUtil.nullValue()) {
            snapshot.setEntryOperationName(entryOperationName);
        } else {
            snapshot.setEntryOperationId(entryOperationId);
        }

        if (firstSpan.getOperationId() == DictionaryUtil.nullValue()) {
            snapshot.setParentOperationName(firstSpan.getOperationName());
        } else {
            snapshot.setParentOperationId(firstSpan.getOperationId());
        }
        return snapshot;
    }

    /**
     * Continue the context from the given snapshot of parent thread.
     *
     * @param snapshot from {@link #capture()} in the parent thread.
     * @see {@link AbstractTracerContext#continued(ContextSnapshot)}
     */
    @Override
    public void continued(ContextSnapshot snapshot) {
        this.segment.ref(new TraceSegmentRef(snapshot));
        this.segment.relatedGlobalTraces(snapshot.getDistributedTraceId());
    }

    /**
     * @return the first global trace id.
     */
    @Override
    public String getReadableGlobalTraceId() {
        return segment.getRelatedGlobalTraces().get(0).toString();
    }

    /**
     * Create an entry span
     *
     * @param operationName most likely a service name
     * @return span instance.
     * @see {@link EntrySpan}
     */
    @Override
    public AbstractSpan createEntrySpan(final String operationName) {
        AbstractTracingSpan entrySpan;
        final AbstractTracingSpan parentSpan = peek();
        final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
        if (parentSpan == null) {
            entrySpan = (AbstractTracingSpan)DictionaryManager.findOperationNameCodeSection()
                .findOnly(segment.getApplicationId(), operationName)
                .doInCondition(new PossibleFound.FoundAndObtain() {
                    @Override public Object doProcess(int operationId) {
                        return new EntrySpan(spanIdGenerator++, parentSpanId, operationId);
                    }
                }, new PossibleFound.NotFoundAndObtain() {
                    @Override public Object doProcess() {
                        return new EntrySpan(spanIdGenerator++, parentSpanId, operationName);
                    }
                });
            entrySpan.start();
            return push(entrySpan);
        } else if (parentSpan.isEntry()) {
            entrySpan = (AbstractTracingSpan)DictionaryManager.findOperationNameCodeSection()
                .findOnly(segment.getApplicationId(), operationName)
                .doInCondition(new PossibleFound.FoundAndObtain() {
                    @Override public Object doProcess(int operationId) {
                        return parentSpan.setOperationId(operationId);
                    }
                }, new PossibleFound.NotFoundAndObtain() {
                    @Override public Object doProcess() {
                        return parentSpan.setOperationName(operationName);
                    }
                });
            return entrySpan.start();
        } else {
            throw new IllegalStateException("The Entry Span can't be the child of Non-Entry Span");
        }
    }

    /**
     * Create a local span
     *
     * @param operationName most likely a local method signature, or business name.
     * @return the span represents a local logic block.
     * @see {@link LocalSpan}
     */
    @Override
    public AbstractSpan createLocalSpan(final String operationName) {
        AbstractTracingSpan parentSpan = peek();
        final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
        AbstractTracingSpan span = (AbstractTracingSpan)DictionaryManager.findOperationNameCodeSection()
            .findOrPrepare4Register(segment.getApplicationId(), operationName)
            .doInCondition(new PossibleFound.FoundAndObtain() {
                @Override
                public Object doProcess(int operationId) {
                    return new LocalSpan(spanIdGenerator++, parentSpanId, operationId);
                }
            }, new PossibleFound.NotFoundAndObtain() {
                @Override
                public Object doProcess() {
                    return new LocalSpan(spanIdGenerator++, parentSpanId, operationName);
                }
            });
        span.start();
        return push(span);
    }

    /**
     * Create an exit span
     *
     * @param operationName most likely a service name of remote
     * @param remotePeer the network id(ip:port, hostname:port or ip1:port1,ip2,port, etc.)
     * @return the span represent an exit point of this segment.
     * @see {@link ExitSpan}
     */
    @Override
    public AbstractSpan createExitSpan(final String operationName, final String remotePeer) {
        AbstractTracingSpan exitSpan;
        AbstractTracingSpan parentSpan = peek();
        if (parentSpan != null && parentSpan.isExit()) {
            exitSpan = parentSpan;
        } else {
            final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
            exitSpan = (AbstractTracingSpan)DictionaryManager.findApplicationCodeSection()
                .find(remotePeer).doInCondition(
                    new PossibleFound.FoundAndObtain() {
                        @Override
                        public Object doProcess(final int peerId) {
                            return DictionaryManager.findOperationNameCodeSection()
                                .findOnly(segment.getApplicationId(), operationName)
                                .doInCondition(
                                    new PossibleFound.FoundAndObtain() {
                                        @Override
                                        public Object doProcess(int operationId) {
                                            return new ExitSpan(spanIdGenerator++, parentSpanId, operationId, peerId);
                                        }
                                    }, new PossibleFound.NotFoundAndObtain() {
                                        @Override
                                        public Object doProcess() {
                                            return new ExitSpan(spanIdGenerator++, parentSpanId, operationName, peerId);
                                        }
                                    });
                        }
                    },
                    new PossibleFound.NotFoundAndObtain() {
                        @Override
                        public Object doProcess() {
                            return DictionaryManager.findOperationNameCodeSection()
                                .findOnly(segment.getApplicationId(), operationName)
                                .doInCondition(
                                    new PossibleFound.FoundAndObtain() {
                                        @Override
                                        public Object doProcess(int operationId) {
                                            return new ExitSpan(spanIdGenerator++, parentSpanId, operationId, remotePeer);
                                        }
                                    }, new PossibleFound.NotFoundAndObtain() {
                                        @Override
                                        public Object doProcess() {
                                            return new ExitSpan(spanIdGenerator++, parentSpanId, operationName, remotePeer);
                                        }
                                    });
                        }
                    });
            push(exitSpan);
        }
        exitSpan.start();
        return exitSpan;
    }

    /**
     * @return the active span of current context, the top element of {@link #activeSpanStack}
     */
    @Override
    public AbstractTracingSpan activeSpan() {
        AbstractTracingSpan span = peek();
        if (span == null) {
            throw new IllegalStateException("No active span.");
        }
        return span;
    }

    /**
     * Stop the given span, if and only if this one is the top element of {@link #activeSpanStack}.
     * Because the tracing core must make sure the span must match in a stack module, like any program did.
     *
     * @param span to finish
     */
    @Override
    public void stopSpan(AbstractSpan span) {
        AbstractTracingSpan lastSpan = peek();
        if (lastSpan == span) {
            if (lastSpan.finish(segment)) {
                pop();
            }
        } else {
            throw new IllegalStateException("Stopping the unexpected span = " + span);
        }

        if (activeSpanStack.isEmpty()) {
            this.finish();
        }
    }

    /**
     * Finish this context, and notify all {@link TracingContextListener}s, managed by {@link
     * TracingContext.ListenerManager}
     */
    private void finish() {
        TraceSegment finishedSegment = segment.finish();
        /**
         * Recheck the segment if the segment contains only one span.
         * Because in the runtime, can't sure this segment is part of distributed trace.
         *
         * @see {@link #createSpan(String, long, boolean)}
         */
        if (!segment.hasRef() && segment.isSingleSpanSegment()) {
            if (!samplingService.trySampling()) {
                finishedSegment.setIgnore(true);
            }
        }
        TracingContext.ListenerManager.notifyFinish(finishedSegment);
    }

    /**
     * The <code>ListenerManager</code> represents an event notify for every registered listener, which are notified
     * when the <cdoe>TracingContext</cdoe> finished, and {@link #segment} is ready for further process.
     */
    public static class ListenerManager {
        private static List<TracingContextListener> LISTENERS = new LinkedList<TracingContextListener>();

        /**
         * Add the given {@link TracingContextListener} to {@link #LISTENERS} list.
         *
         * @param listener the new listener.
         */
        public static synchronized void add(TracingContextListener listener) {
            LISTENERS.add(listener);
        }

        /**
         * Notify the {@link TracingContext.ListenerManager} about the given {@link TraceSegment} have finished.
         * And trigger {@link TracingContext.ListenerManager} to notify all {@link #LISTENERS} 's
         * {@link TracingContextListener#afterFinished(TraceSegment)}
         *
         * @param finishedSegment
         */
        static void notifyFinish(TraceSegment finishedSegment) {
            for (TracingContextListener listener : LISTENERS) {
                listener.afterFinished(finishedSegment);
            }
        }

        /**
         * Clear the given {@link TracingContextListener}
         */
        public static synchronized void remove(TracingContextListener listener) {
            LISTENERS.remove(listener);
        }

    }

    /**
     * @return the top element of 'ActiveSpanStack', and remove it.
     */
    private AbstractTracingSpan pop() {
        return activeSpanStack.removeLast();
    }

    /**
     * Add a new Span at the top of 'ActiveSpanStack'
     *
     * @param span
     */
    private AbstractTracingSpan push(AbstractTracingSpan span) {
        activeSpanStack.addLast(span);
        return span;
    }

    /**
     * @return the top element of 'ActiveSpanStack' only.
     */
    private AbstractTracingSpan peek() {
        if (activeSpanStack.isEmpty()) {
            return null;
        }
        return activeSpanStack.getLast();
    }

    private AbstractTracingSpan first() {
        return activeSpanStack.getFirst();
    }
}
