import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
const room={session:{roomId:10,myParticipantId:100}};
globalThis.__recoveryTestRoom=room;
registerHooks({resolve(specifier,context,next) {
 if(specifier==='@/entities/performance')return {url:new URL('../src/entities/performance/wsEvents.ts',import.meta.url).href,shortCircuit:true};
 if(specifier==='@/entities/room')return {url:'data:text/javascript,export const useRoomStore={getState:()=>globalThis.__recoveryTestRoom}',shortCircuit:true};
 if(specifier==='./connectionInterruption')return {url:new URL('../src/widgets/performance-room/model/connectionInterruption.ts',import.meta.url).href,shortCircuit:true};
 return next(specifier,context);
}});
const {useStageStore:store}=await import('../src/widgets/performance-room/model/stageStore.ts');
const {captureInterruption,clearInterruption,readInterruption}=await import('../src/widgets/performance-room/model/connectionInterruption.ts');
const storage=new Map();globalThis.window={sessionStorage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}};
const snapshot=(id=30,status='PLAYING')=>({name:'room',participants:[],serverNow:new Date().toISOString(),performance:{performanceId:id,performerParticipantId:100,status,songId:20,songTitle:'song',artist:'test',songDurationMs:180000,settings:{},suspendedAt:status==='SUSPENDED'?new Date().toISOString():null},playback:{playbackPositionMs:40000}});
beforeEach(()=>{const pending=readInterruption();if(pending)clearInterruption(pending.interruptionId);store.getState().endStage();room.session={roomId:10,myParticipantId:100};});
test('performer stays frozen when reconnect first returns an old PLAYING snapshot',()=>{
 const frozen=captureInterruption({roomId:10,participantId:100,performanceId:30,interruptedAt:new Date().toISOString()},22000);
 store.setState({performanceId:30,phase:'PERFORMING',isSuspended:true,localInterruption:frozen});
 store.getState().hydrateFromRoomSnapshot(snapshot());
 assert.equal(store.getState().isSuspended,true);assert.equal(store.getState().resumeOffsetMs,22000);
});
test('spectator reconnect uses current snapshot without adopting a performer interruption',()=>{
 captureInterruption({roomId:10,participantId:100,performanceId:30,interruptedAt:new Date().toISOString()},22000);
 room.session.myParticipantId=200;
 store.getState().hydrateFromRoomSnapshot(snapshot());
 assert.equal(store.getState().isSuspended,false);assert.equal(store.getState().localInterruption,null);assert.equal(store.getState().resumeOffsetMs,40000);
});
test('a new performance supersedes the old pending interruption',()=>{
 const frozen=captureInterruption({roomId:10,participantId:100,performanceId:30,interruptedAt:new Date().toISOString()},22000);
 store.setState({performanceId:30,localInterruption:frozen,isSuspended:true});
 store.getState().hydrateFromRoomSnapshot(snapshot(31));
 assert.equal(store.getState().performanceId,31);assert.equal(store.getState().localInterruption,null);assert.equal(store.getState().isSuspended,false);
});
test('resume received before reconciliation cannot unfreeze a pending performer',()=>{
 const frozen=captureInterruption({roomId:10,participantId:100,performanceId:30,interruptedAt:new Date().toISOString()},22000);
 store.setState({performanceId:30,isSuspended:true,localInterruption:frozen});
 store.getState().applyPerformanceResumed({performanceId:30,performerParticipantId:100,currentStatus:'PLAYING',resumePositionMs:40000,settings:{},resumeAt:new Date().toISOString()});
 assert.equal(store.getState().isSuspended,true);assert.equal(store.getState().localInterruption,frozen);
});
test('late suspend and resume events from an old performance are ignored',()=>{
 store.setState({performanceId:31,isSuspended:false});
 store.getState().applyPerformanceSuspended({performanceId:30,suspendedAt:new Date().toISOString(),playbackPositionMs:20000});
 assert.equal(store.getState().isSuspended,false);
 store.getState().applyPerformanceResumed({performanceId:30,settings:{},currentStatus:'PLAYING',resumePositionMs:20000});
 assert.equal(store.getState().performanceId,31);
});

test('an old resume cannot override a later suspension of the same performance',()=>{
 store.setState({performanceId:30,isSuspended:true,suspendedAt:'2026-11-23T00:00:20Z'});
 store.getState().applyPerformanceResumed({performanceId:30,resumeAt:'2026-11-23T00:00:10Z',currentStatus:'PLAYING',settings:{},resumePositionMs:10000});
 assert.equal(store.getState().isSuspended,true);
});
test('a delayed suspension cannot override a later resume of the same performance',()=>{
 store.setState({performanceId:30,isSuspended:false,resumeAt:'2026-11-23T00:00:20.500Z'});
 store.getState().applyPerformanceSuspended({performanceId:30,suspendedAt:'2026-11-23T00:00:10Z',playbackPositionMs:10000});
 assert.equal(store.getState().isSuspended,false);
});
