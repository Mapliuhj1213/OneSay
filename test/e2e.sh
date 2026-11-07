#!/bin/sh
# 端到端：本地中转（../onesay-relay，wrangler dev）+ 电脑端（测试能力）+ 命令行下令。
# 用法：RELAY_DIR=../onesay-relay sh test/e2e.sh   （需已 build.sh，JAR 默认 build/onesay-desktop.jar）
set -u
cd "$(dirname "$0")/.."
RELAY_DIR=${RELAY_DIR:-../onesay-relay}
JAR=${JAR:-build/onesay-desktop.jar}
W=$(mktemp -d)
export NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost
TOKEN=$(sed -n 's/^RELAY_TOKEN=//p' "$RELAY_DIR/.dev.vars")
pass=0; fail=0
ok() { if [ "$1" = 0 ]; then pass=$((pass+1)); echo "ok   $2"; else fail=$((fail+1)); echo "FAIL $2"; fi; }

for p in $(pgrep workerd); do kill $p 2>/dev/null; done; sleep 1
(cd "$RELAY_DIR" && rm -rf .wrangler/state && npx wrangler dev --port 8787 --ip 127.0.0.1 > "$W/relay.log" 2>&1) &
RP=$!
for i in $(seq 1 60); do grep -q "Ready on" "$W/relay.log" 2>/dev/null && curl -s 127.0.0.1:8787/healthz >/dev/null && break; sleep 1; done

# 电脑端配置（不走交互 setup，直接写）
KEY=$(python3 -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('='))")
cat > "$W/pc.properties" <<EOF
relay_url=http\://127.0.0.1\:8787
relay_token=$TOKEN
device_id=pc-test
kind=pc
pair_key=$KEY
EOF
PAIR=$(java -jar "$JAR" --config "$W/pc.properties" pair | tail -1)
java -jar "$JAR" --config "$W/cli.properties" join "$PAIR" --id cli-test > /dev/null
ok $? "命令行导入配对串"

# 电脑没开：应报离线
java -jar "$JAR" --config "$W/cli.properties" send pc-test ping > "$W/o0" 2>/dev/null
[ $? = 2 ] && grep -q "离线" "$W/o0"; ok $? "电脑未开时提示离线"

ONESAY_TEST_ACTIONS=1 java -Djava.awt.headless=true -jar "$JAR" --config "$W/pc.properties" run --console > "$W/pc.log" 2>&1 &
PP=$!
for i in $(seq 1 20); do grep -q "在线" "$W/pc.log" && break; sleep 1; done

java -jar "$JAR" --config "$W/cli.properties" send pc-test ping > "$W/o1" 2>/dev/null
[ $? = 0 ] && grep -q "成功：在线" "$W/o1"; ok $? "ping 往返"

java -jar "$JAR" --config "$W/cli.properties" send pc-test screenshot --out "$W/shots" > "$W/o2" 2>/dev/null
[ $? = 0 ] && ls "$W"/shots/*.jpg >/dev/null 2>&1 && python3 -c "
import sys,glob; d=open(glob.glob('$W/shots/*.jpg')[0],'rb').read(); sys.exit(0 if d[:3]==b'\xff\xd8\xff' and len(d)>1000 else 1)"
ok $? "截图回传并存成 JPEG"

java -jar "$JAR" --config "$W/cli.properties" send pc-test echo text=你好 n=3 flag=true > "$W/o3" 2>/dev/null
grep -q '"n":3' "$W/o3" && grep -q '你好' "$W/o3" && grep -q '"flag":true' "$W/o3"; ok $? "参数原样送达（中文、数字、布尔）"

java -jar "$JAR" --config "$W/cli.properties" send pc-test format_disk > "$W/o4" 2>/dev/null
[ $? = 1 ] && grep -q "不支持的指令" "$W/o4"; ok $? "未知指令被拒"

# 错误密钥：伪造方有中转令牌但没有配对密钥
sed "s/^pair_key=.*/pair_key=$(python3 -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('='))")/; s/^device_id=.*/device_id=evil/" "$W/cli.properties" > "$W/evil.properties"
java -jar "$JAR" --config "$W/evil.properties" send pc-test screenshot > "$W/o5" 2>/dev/null
[ $? != 0 ] && grep -q "BAD_SIG" "$W/o5" && grep -q "拒绝来自 evil" "$W/pc.log" && ! grep -q "执行 screenshot（来自 evil" "$W/pc.log"
ok $? "错密钥的指令被拒且未执行"

# 重放：抓一条合法指令原样再发
python3 - "$TOKEN" "$W" <<'PY'
import asyncio, json, sys, time, hmac, hashlib, base64, secrets, websockets
tok, w = sys.argv[1], sys.argv[2]
props = dict(l.split('=',1) for l in open(w+'/cli.properties', encoding='utf-8').read().splitlines() if '=' in l and not l.startswith('#'))
key = base64.urlsafe_b64decode(props['pair_key'] + '=' * (-len(props['pair_key']) % 4))
def canon(o):
    return json.dumps(o, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
async def main():
    async with websockets.connect('ws://127.0.0.1:8787/v1/ws?device=cli-test&kind=other', additional_headers={'Authorization': 'Bearer ' + tok}) as ws:
        await ws.recv()
        body = {'kind':'cmd','cmd':'ping','args':{},'v':1,'ts':int(time.time()*1000),'nonce':secrets.token_urlsafe(12)}
        mid = 'mreplay1'
        body['sig'] = base64.urlsafe_b64encode(hmac.new(key, ('onesay.v1\n%s\ncli-test\npc-test\n' % mid + canon(body)).encode(), hashlib.sha256).digest()).decode().rstrip('=')
        env = json.dumps({'type':'msg','to':'pc-test','id':mid,'body':body})
        res = []
        for _ in range(2):
            await ws.send(env)
            while True:
                m = json.loads(await asyncio.wait_for(ws.recv(), 10))
                if m.get('type') == 'msg': res.append(m['body']); break
        ok = res[0].get('ok') is True and res[1].get('ok') is False and 'REPLAY' in res[1].get('text','')
        sys.exit(0 if ok else 1)
asyncio.run(main())
PY
ok $? "Python 独立实现的签名被接受（协议可跨语言），原样重放被拒"

# 电脑关掉：命令行应很快看到离线
kill $PP; sleep 2
java -jar "$JAR" --config "$W/cli.properties" send pc-test ping > "$W/o6" 2>/dev/null
[ $? = 2 ]; ok $? "电脑退出后提示离线"

kill $RP 2>/dev/null; for p in $(pgrep workerd); do kill $p 2>/dev/null; done
echo "通过 $pass，失败 $fail"
[ -n "${KEEP:-}" ] && echo "W=$W" || rm -rf "$W"
[ $fail = 0 ]
