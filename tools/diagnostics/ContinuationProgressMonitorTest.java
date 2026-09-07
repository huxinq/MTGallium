import java.util.*;
import org.mtgallium.evaluation.searchteacher.*;

/** Synthetic protocol checks; no historical files or gameplay. */
public class ContinuationProgressMonitorTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static PairedSequentialRule rule(int cap,boolean futility){return new PairedSequentialRule(1,"independent-seed-pair-mean-v1",.5,.5,.025,.025,cap,List.of(.2,.5,.8),futility);}
    public static void main(String[]args){
        check(!ContinuationProgressMonitor.terminalExecution("not-started"),"pre-execution state must keep monitoring");
        check(!ContinuationProgressMonitor.terminalExecution("running"),"running state");
        check(ContinuationProgressMonitor.terminalExecution("succeeded")&&ContinuationProgressMonitor.terminalExecution("failed"),"terminal execution states");
        var binding=ContinuationProgressMonitor.snapshotBindings("synthetic-setup",List.of(),"a".repeat(64),52);
        check(!binding.getIdentity().equals(ContinuationProgressMonitor.snapshotBindings("synthetic-setup",List.of(),"a".repeat(64),56).getIdentity()),"source artifact authority accepts binding names and distinguishes executed populations");
        var tied=List.of(new PairedSequentialScore(7,.5,List.of()),new PairedSequentialScore(8,.5,List.of()));
        var cap=ContinuationProgressMonitor.forecast(rule(10,false),tied,7,3,32,100);
        check((double)cap.get("expectedAdditionalExecutedGames")==16,"cap includes only eight future pairs");
        check((double)cap.get("expectedAdditionalInspectedGames")==16,"inspection cap");
        check((double)cap.get("upperOnlyStopProbability")==0,"cap is not success");
        check(cap.get("stopDispositions").equals(Map.of("BUDGET_EXHAUSTED",32)),"exhaustion retained");
        var futile=ContinuationProgressMonitor.forecast(rule(32,true),tied,7,4,32,100);
        check(futile.get("stopDispositions").equals(Map.of("FUTILITY",32)),"futility is a stopping condition, not success");
        check((double)futile.get("expectedAdditionalExecutedGames")<60,"futility reduces work below full cap");
        var won=List.of(new PairedSequentialScore(7,1.0,List.of()));
        var forecast=ContinuationProgressMonitor.forecast(rule(32,true),won,7,4,32,200);
        var full=new ArrayList<PairedSequentialScore>();for(int i=0;i<32;i++)full.add(new PairedSequentialScore(7+i,1.0,List.of()));
        var exact=PairedSequentialTestKt.pairedSequentialTest(rule(32,true),full,7);
        int future=exact.getInspectedPairs()-won.size();
        check((double)forecast.get("expectedAdditionalInspectedGames")==future*2,"uses source stopping boundary");
        check((double)forecast.get("expectedAdditionalExecutedGames")==2*((future+3)/4)*4,"batch overshoot retained in workload forecast");
        check((double)forecast.get("upperOnlyStopProbability")==1,"deterministic favorable future");
        var finished=ContinuationProgressMonitor.forecast(rule(32,true),full.subList(0,exact.getInspectedPairs()),7,4,32,200);
        check((double)finished.get("expectedAdditionalExecutedGames")==0,"already stopped is zero work");
        boolean refused=false;try{ContinuationProgressMonitor.forecast(rule(32,true),List.of(new PairedSequentialScore(7,null,List.of("failure"))),7,4,32,0);}catch(IllegalStateException e){refused=true;}
        check(refused,"non-game failure cannot be resampled as an outcome");
        var unordered=new LinkedHashMap<String,Object>();unordered.put("z","line\nquote\"");unordered.put("a",List.of(1,2));
        var reversed=new LinkedHashMap<String,Object>();reversed.put("a",List.of(1,2));reversed.put("z","line\nquote\"");
        check(ContinuationProgressMonitor.json(unordered).equals(ContinuationProgressMonitor.json(reversed)),"stable snapshot identity independent of map order");
        System.out.println("PASS cap/futility source forecasts, preserved prefix, batch overshoot, stopped and invalid outcomes, deterministic JSON.");
    }
}
