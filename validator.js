#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const SNAPSHOT_DIR = path.resolve('./snapshot');

function loadFiles(dir) {
  if (!fs.existsSync(dir)) {
    console.error(`Pasta não encontrada: ${dir}`);
    process.exit(2);
  }
  const files = fs.readdirSync(dir).filter(f => f.toLowerCase().endsWith('.json'));
  if (files.length === 0) {
    console.error(`Nenhum .json em ${dir}`);
    process.exit(2);
  }
  return files.map(f => ({ name: f, full: path.join(dir, f) }));
}

function parseSnapshotFile(file) {
  try {
    const obj = JSON.parse(fs.readFileSync(file.full, 'utf-8'));
    if (!obj || typeof obj.snapshot !== 'object') {
      return { ok: false, error: 'Campo "snapshot" ausente ou inválido' };
    }
    const processes = Object.entries(obj.snapshot).map(([pid, st]) => ({
      processId: parseInt(pid, 10),
      isInCriticalSection: !!st.isInCriticalSection,
      wantsCriticalSection: !!st.wantsCriticalSection,
      requestsThisProcessMustReplyTo: Array.isArray(st.requestsThisProcessMustReplyTo)
        ? st.requestsThisProcessMustReplyTo.map(Number)
        : [],
      repliesThisProcessIsWaitingOn: Array.isArray(st.repliesThisProcessIsWaitingOn)
        ? st.repliesThisProcessIsWaitingOn.map(Number)
        : [],
      messagesThatWereInTransit:
        (st.messagesThatWereInTransit && typeof st.messagesThatWereInTransit === 'object')
          ? Object.fromEntries(
              Object.entries(st.messagesThatWereInTransit)
                .map(([k, v]) => [String(k), Array.isArray(v) ? v : []])
            )
          : {},
      _raw: st
    }));
    processes.sort((a, b) => a.processId - b.processId);
    const N = processes.length;
    return { ok: true, processes, N };
  } catch (e) {
    return { ok: false, error: `JSON inválido: ${e.message}` };
  }
}

function inv1(processes) {
  const inSC = processes.filter(p => p.isInCriticalSection);
  return { name: 'inv1', ok: inSC.length <= 1, details: { countInSC: inSC.length, pids: inSC.map(p => p.processId) } };
}

function inv2(processes) {
  const allDontWant = processes.every(p => !p.wantsCriticalSection);
  if (!allDontWant) return { name: 'inv2', ok: true, details: { reason: 'não se aplica pois existe processo querendo SC' } };
  const noWaitingLists = processes.every(p => p.repliesThisProcessIsWaitingOn.length === 0);
  const noPendingRepliesToSend = processes.every(p => p.requestsThisProcessMustReplyTo.length === 0);
  const noTransitAnywhere = processes.every(p =>
    Object.values(p.messagesThatWereInTransit).every(arr => arr.length === 0)
  );
  return {
    name: 'inv2',
    ok: noWaitingLists && noPendingRepliesToSend && noTransitAnywhere,
    details: { allDontWant, noWaitingLists, noPendingRepliesToSend, noTransitAnywhere }
  };
}

function inv3(processes) {
  for (const p of processes) {
    for (const q of p.repliesThisProcessIsWaitingOn) {
      if (!(p.isInCriticalSection || p.wantsCriticalSection)) {
        return { name: 'inv3', ok: false, details: { p: p.processId, q } };
      }
    }
  }
  return { name: 'inv3', ok: true, details: {} };
}

function peersDevendoPara(processes, targetPid) {
  let total = 0;
  const peers = [];
  for (const r of processes) {
    if (r.processId === targetPid) continue;
    if (r.requestsThisProcessMustReplyTo.includes(targetPid)) {
      total += 1;
      peers.push(r.processId);
    }
  }
  return { total, peers };
}

function repliesEmTransitoPara(processes, targetPid) {
  const key = String(targetPid);
  const re = new RegExp(`\\b${targetPid}\\b`);
  let total = 0;
  const peers = [];
  for (const r of processes) {
    if (r.processId === targetPid) continue;
    const arr = r.messagesThatWereInTransit[key] || [];
    const hasReply = arr.some(msg => {
      const s = String(msg).toUpperCase();
      return s.includes('REPLY') && re.test(s);
    });
    if (hasReply) {
      total += 1;
      peers.push(r.processId);
    }
  }
  return { total, peers };
}

function inv4(processes) {
  const N = processes.length;
  for (const p of processes) {
    if (p.wantsCriticalSection && !p.isInCriticalSection) {
      const { total: owing, peers: peersDevendo } = peersDevendoPara(processes, p.processId);
      const { total: inTransit, peers: peersComReplyEmTransito } = repliesEmTransitoPara(processes, p.processId);
      const others = processes.filter(q => q.processId !== p.processId).map(q => q.processId);
      const pending = new Set([...peersDevendo, ...peersComReplyEmTransito]);
      const received = others.filter(q => !pending.has(q)).length;
      const lhs = received + inTransit + owing;
      if (lhs !== N - 1) {
        return {
          name: 'inv4',
          ok: false,
          details: {
            p: p.processId,
            expected: N - 1,
            got: lhs,
            breakdown: { received, repliesInTransitToP: inTransit, owingToP: owing },
            debug: {
              whoReceived: others.filter(q => !pending.has(q)),
              whoInTransit: peersComReplyEmTransito,
              whoOwing: peersDevendo,
              waitingList: p.repliesThisProcessIsWaitingOn
            }
          }
        };
      }
    }
  }
  return { name: 'inv4', ok: true, details: {} };
}

function main() {
  const files = loadFiles(SNAPSHOT_DIR);
  let okCount = 0;
  let errCount = 0;
  for (const file of files) {
    const parsed = parseSnapshotFile(file);
    if (!parsed.ok) {
      console.log(`aviso ignorando ${file.name}: ${parsed.error}`);
      continue;
    }
    const { processes, N } = parsed;
    const r1 = inv1(processes);
    const r2 = inv2(processes);
    const r3 = inv3(processes);
    const r4 = inv4(processes);
    const results = [r1, r2, r3, r4];
    const fails = results.filter(r => !r.ok);
    if (fails.length === 0) {
      okCount++;
      console.log(`OK: ${file.name} N=${N}`);
    } else {
      errCount++;
      console.log(`ERRO: ${file.name} N=${N}`);
      for (const b of fails) {
        console.log(`   ${b.name} falhou:`, b.details);
      }
    }
  }
  console.log(`Resumo: ${okCount} OK e ${errCount} com problema`);
  process.exit(errCount ? 1 : 0);
}

main();
