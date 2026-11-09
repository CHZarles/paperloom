#!/bin/bash
# 聊天 e2e：两轮 CHAT（验证历史注入）+ 入库轮 + 沉淀轮。对着桩 Java + 真 MiniMax。
set -e
BASE=http://127.0.0.1:8092/v1/discovery/turns
AUTH="Authorization: Bearer c1-e2e-token"
post() { curl -s -X POST "$BASE" -H "$AUTH" -H "Content-Type: application/json" -d "$1"; }
get()   { curl -s "$BASE/$1" -H "$AUTH"; }
wait_terminal() {
  for _ in $(seq 1 40); do
    sleep 4
    if ! echo "$(get $1)" | grep -q '"status":"RUNNING"'; then get "$1"; return; fi
  done
  get "$1"
}

echo "== turn1: CHAT（自我介绍 + 检索任务）=="
post '{"turnId":"chat-t1","userId":"u1","kind":"CHAT","lastMessage":"我叫查尔斯。帮我找两篇 KV cache 量化的近期代表论文，简单介绍为什么值得看。","history":[],"memory":[],"historyCardIds":[]}' > /dev/null
wait_terminal chat-t1 | python3 -c "import json,sys; d=json.load(sys.stdin); print('status:',d['status']); print('searches:',d['searchCount'],'cards:',len(d['cards'])); print('text:',d['assistantText'][:180].replace(chr(10),' '))"

echo; echo "== turn2: CHAT（跨轮记忆验证：问名字）=="
post '{"turnId":"chat-t2","userId":"u1","kind":"CHAT","lastMessage":"我刚才说我叫什么名字？一句话回答。","history":[{"role":"USER","text":"我叫查尔斯。帮我找两篇 KV cache 量化的近期代表论文。"},{"role":"ASSISTANT","text":"推荐：KIVI（2402.02750）与 KVQuant（2401.18079）。"}],"memory":[],"historyCardIds":[]}' > /dev/null
wait_terminal chat-t2 | python3 -c "import json,sys; d=json.load(sys.stdin); print('status:',d['status']); print('text:',d['assistantText'][:160].replace(chr(10),' '))"

echo; echo "== turn3: CHAT 入库轮 =="
post '{"turnId":"chat-t3","userId":"u1","kind":"CHAT","lastMessage":"把这两篇：2402.02750 和 2401.18079，都入库。","history":[{"role":"ASSISTANT","text":"推荐：KIVI（2402.02750）与 KVQuant（2401.18079）。"}],"memory":[],"historyCardIds":["2402.02750","2401.18079"]}' > /dev/null
wait_terminal chat-t3 | python3 -c "import json,sys; d=json.load(sys.stdin); print('status:',d['status']); print('import:',d['importResults']); print('text:',d['assistantText'][:120].replace(chr(10),' '))"

echo; echo "== turn4: DISTILL 沉淀轮 =="
post '{"turnId":"chat-t4","userId":"u1","kind":"DISTILL","lastMessage":"","history":[{"role":"USER","text":"我叫查尔斯。帮我找两篇 KV cache 量化的近期代表论文"},{"role":"ASSISTANT","text":"推荐：KIVI（2402.02750）与 KVQuant（2401.18079）。"}],"memory":[],"historyCardIds":[]}' > /dev/null
wait_terminal chat-t4 | python3 -c "import json,sys; d=json.load(sys.stdin); print('status:',d['status']); print('saved:',d['savedMemory']); print('text:',d['assistantText'][:160].replace(chr(10),' '))"

echo; echo "== cleanup =="
curl -s -X DELETE "$BASE/chat-t1" -H "$AUTH" > /dev/null
curl -s -X DELETE "$BASE/chat-t2" -H "$AUTH" > /dev/null
curl -s -X DELETE "$BASE/chat-t3" -H "$AUTH" > /dev/null
curl -s -X DELETE "$BASE/chat-t4" -H "$AUTH" > /dev/null
echo done
