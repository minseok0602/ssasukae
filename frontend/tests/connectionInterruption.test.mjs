import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { captureInterruption, clearInterruption, readInterruption, registerPerformerEngine } from '../src/widgets/performance-room/model/connectionInterruption.ts';
const storage=new Map();
globalThis.window={sessionStorage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}};
beforeEach(()=>{const old=readInterruption();if(old)clearInterruption(old.interruptionId);registerPerformerEngine(null);storage.clear();});
const input={roomId:10,performanceId:30,participantId:100,interruptedAt:'2026-11-23T00:00:00Z'};
test('captures the actual audio position before stopping playback and the microphone',()=>{
 const operations=[];
 registerPerformerEngine({getMrPositionMs:()=>{operations.push('position');return 32123.9;},stopMr:()=>operations.push('stop'),closeMic:()=>operations.push('mic')});
 const frozen=captureInterruption(input,0);
 assert.equal(frozen.playbackPositionMs,32123);assert.deepEqual(operations,['position','stop','mic']);
 assert.equal(readInterruption().interruptionId,frozen.interruptionId);
});
test('repeated connection failures preserve the first interruption and do not extend grace',()=>{
 const first=captureInterruption(input,12000);
 const repeated=captureInterruption({...input,interruptedAt:'2026-11-23T00:00:10Z'},19000);
 assert.deepEqual(first,repeated);
});
test('acknowledgement from an old interruption cannot clear a new one',()=>{
 const first=captureInterruption(input,10000);
 clearInterruption(first.interruptionId);
 const second=captureInterruption({...input,performanceId:31},2000);
 clearInterruption(first.interruptionId);
 assert.equal(readInterruption().interruptionId,second.interruptionId);
});
test('new performances do not inherit the old playback position',()=>{
 captureInterruption(input,10000);
 const next=captureInterruption({...input,performanceId:31},500);
 assert.equal(next.performanceId,31);assert.equal(next.playbackPositionMs,500);
});
test('storage failure still leaves a recoverable in-memory interruption',()=>{
 const original=window.sessionStorage.setItem;window.sessionStorage.setItem=()=>{throw new Error('disabled');};
 try {const frozen=captureInterruption(input,8000);assert.equal(readInterruption().interruptionId,frozen.interruptionId);}
 finally {window.sessionStorage.setItem=original;}
});
