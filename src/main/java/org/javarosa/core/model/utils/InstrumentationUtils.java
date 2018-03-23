package org.javarosa.core.model.utils;

import org.javarosa.core.model.trace.EvaluationTrace;
import org.javarosa.core.model.trace.EvaluationTraceReporter;
import org.javarosa.core.model.trace.EvaluationTraceSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Utility functions for instrumentation in the engine
 *
 * Created by ctsims on 7/6/2017.
 */

public class InstrumentationUtils {

    public static void printAndClearTraces(EvaluationTraceReporter reporter, String description) {
        printAndClearTraces(reporter, description, EvaluationTraceSerializer.TraceInfoType.FULL_PROFILE);
    }

    /**
     * Prints out traces (if any exist) from the provided reporter with a description into sysout
     */
    public static void printAndClearTraces(EvaluationTraceReporter reporter, String description,
                                           EvaluationTraceSerializer.TraceInfoType requestedInfo) {
        if (reporter != null) {
            if (reporter.wereTracesReported()) {
                System.out.println(description);
            }

            EvaluationTraceSerializer serializer = new EvaluationTraceSerializer();

            for (EvaluationTrace trace : reporter.getCollectedTraces()) {
                System.out.println(trace.getExpression() + ": " + trace.getValue());
                System.out.print(serializer.serializeEvaluationTrace(trace, requestedInfo, reporter.reportAsFlat()));
            }

            reporter.reset();
        }
    }

    /**
     * Prints out traces (if any exist) from the provided reporter with a description into sysout
     */
    public static String collectAndClearTraces(EvaluationTraceReporter reporter, String description,
                                               EvaluationTraceSerializer.TraceInfoType requestedInfo) {
        String returnValue = "";
        if (reporter != null) {
            if (reporter.wereTracesReported()) {
                returnValue += description + "\n";
            }

            EvaluationTraceSerializer serializer = new EvaluationTraceSerializer();

            for (EvaluationTrace trace : reporter.getCollectedTraces()) {
                returnValue += trace.getExpression() + ": " + trace.getValue()  + "\n";
                returnValue += serializer.serializeEvaluationTrace(trace, requestedInfo, reporter.reportAsFlat());
            }

            reporter.reset();
        }
        return returnValue;
    }

    public static void printExpressionsThatUsedCaching(EvaluationTraceReporter reporter, String description) {
        if (reporter != null) {
            if (reporter.wereTracesReported()) {
                System.out.println(description);
            }

            for (EvaluationTrace trace : reporter.getCollectedTraces()) {
                if (trace.evaluationUsedExpressionCache()) {
                    System.out.println(trace.getExpression() + ": " + trace.getValue());
                    System.out.println("    " + trace.getCacheReport());
                }
            }
        }
    }

    public static void printCachedAndNotCachedExpressions(EvaluationTraceReporter reporter, String description) {
        if (reporter != null) {
            if (reporter.wereTracesReported()) {
                System.out.println(description);
            }

            List<EvaluationTrace> withCaching = new ArrayList<>();
            List<EvaluationTrace> withoutCaching = new ArrayList<>();
            for (EvaluationTrace trace : reporter.getCollectedTraces()) {
                if (trace.evaluationUsedExpressionCache()) {
                    withCaching.add(trace);
                } else {
                    withoutCaching.add(trace);
                }
            }

            System.out.println("EXPRESSIONS NEVER CACHED: " + withoutCaching.size());
            for (EvaluationTrace trace : withoutCaching) {
                System.out.println(trace.getExpression() + ": " + trace.getValue());
            }

            System.out.println("EXPRESSIONS CACHED: " + withoutCaching.size());
            for (EvaluationTrace trace : withCaching) {
                System.out.println(trace.getExpression() + ": " + trace.getValue());
                System.out.println("    " + trace.getCacheReport());
            }
        }
    }

}
