package app.kinframe.frame.remotehelp

/**
 * The root side of remote help (docs/spec/remote-help.md §5.4, §8): one shell script with a command per step, which the
 * app copies to `/data/local/tmp/kinframe-ts/rh.sh` and runs through `su 0 sh` (stdin), never `su 0 sh -c "…"`:
 * this firmware's `su` passes only the first word of that.
 *
 * What the prototype taught:
 * - Background processes die with the shell that started them unless started as `( setsid nohup … & )` from a
 *   subshell, and the script waits a few seconds before it ends.
 * - `stop adbd` kills every shell adbd started, so the adbd restart is a detached script that sleeps first.
 * - Processes the app started live in the app's cgroup and die with it: the script moves itself to the root cgroup.
 *
 * Written for Android 6's mksh and toolbox: no awk, head, tail, tr, printf or timeout (echo is safe for
 * ADB keys: base64 and user@host, no backslashes). Commands print `KEY=value` lines for
 * the app (`IP=`, `ERROR=`, `PRESENT=`, `SESSION=`, `RUNNING=`) and exit non-zero on failure.
 *
 * Paths used for tests only: `KINFRAME_ADB_KEYS`, `KINFRAME_SYSTEM`, `KINFRAME_MOUNTS`, `KINFRAME_ACCT`.
 */
object RemoteHelpScript {
    /** Where everything lives on the frame (root only, mode 700). */
    const val DIR = "/data/local/tmp/kinframe-ts"

    /** Single-quotes [s] for sh. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** The command line handed to `su 0 sh`: put the script in [dir] and run [command] with [args]. */
    fun commandLine(scriptSource: String, dir: String, command: String, vararg args: String): String {
        val d = quote(dir)
        val run = (listOf("sh", quote("$dir/rh.sh"), command) + args.map(::quote)).joinToString(" ")
        return "mkdir -p $d && chmod 700 $d && cp ${quote(scriptSource)} $d/rh.sh && chmod 700 $d/rh.sh && $run"
    }

    val SCRIPT = """
        |# kinframe remote help (docs/spec/remote-help.md §5.4). Run as root: sh rh.sh <command> [args]
        |D="${'$'}{0%/*}"
        |CMD="${'$'}1"; shift
        |KEYS="${'$'}{KINFRAME_ADB_KEYS:-/data/misc/adb/adb_keys}"
        |SYS="${'$'}{KINFRAME_SYSTEM:-/system}"
        |MOUNTS="${'$'}{KINFRAME_MOUNTS:-/proc/mounts}"
        |ACCT="${'$'}{KINFRAME_ACCT:-/acct/tasks}"
        |SOCK="${'$'}D/ts.sock"
        |CONF="${'$'}SYS/etc/resolv.conf"
        |MARK="# kinframe remote help"
        |
        |fail() { echo "ERROR=${'$'}*"; exit 1; }
        |# Leave the app's cgroup: Android kills everything in it when the app's process goes.
        |detach() { [ -e "${'$'}ACCT" ] && { echo ${'$'}${'$'} > "${'$'}ACCT"; } 2>/dev/null; }
        |lastline() { L=""; while IFS= read -r x || [ -n "${'$'}x" ]; do [ -n "${'$'}x" ] && L="${'$'}x"; done < "${'$'}1"; echo "${'$'}L"; }
        |ours() { [ -r "/proc/${'$'}1/cmdline" ] && grep -q tailscaled "/proc/${'$'}1/cmdline" 2>/dev/null && grep -qF "${'$'}D/" "/proc/${'$'}1/cmdline" 2>/dev/null; }
        |running() { P=${'$'}(cat "${'$'}D/tailscaled.pid" 2>/dev/null); [ -n "${'$'}P" ] && ours "${'$'}P"; }
        |sysdev() { while read -r dev mnt rest; do [ "${'$'}mnt" = "${'$'}SYS" ] && { echo "${'$'}dev"; return; }; done < "${'$'}MOUNTS"; }
        |ourconf() { [ -f "${'$'}CONF" ] && { read -r first < "${'$'}CONF"; [ "${'$'}first" = "${'$'}MARK" ]; }; }
        |haskey() { [ -f "${'$'}KEYS" ] || return 1; while IFS= read -r l || [ -n "${'$'}l" ]; do [ "${'$'}l" = "${'$'}1" ] && return 0; done < "${'$'}KEYS"; return 1; }
        |# adbd reads the TCP port when it starts; restarting it from here would kill this shell if adbd started it.
        |adbrestart() {
        |  { echo 'sleep 2'; echo 'setprop service.adb.tcp.port "${'$'}1"'; echo 'stop adbd'; echo 'sleep 1'; echo 'start adbd'; } > "${'$'}D/adbtcp.sh"
        |  ( setsid nohup sh "${'$'}D/adbtcp.sh" "${'$'}1" > /dev/null 2>&1 < /dev/null & )
        |  sleep 3
        |}
        |
        |case "${'$'}CMD" in
        |has)
        |  [ -x "${'$'}D/v-${'$'}1/tailscaled" ] && [ -x "${'$'}D/v-${'$'}1/tailscale" ] && echo "PRESENT=1"
        |  exit 0 ;;
        |install)
        |  V="${'$'}D/v-${'$'}1"
        |  mkdir -p "${'$'}V" || fail "mkdir failed"
        |  for b in tailscaled tailscale; do
        |    cp "${'$'}2/${'$'}b" "${'$'}V/${'$'}b.new" && chmod 755 "${'$'}V/${'$'}b.new" && mv "${'$'}V/${'$'}b.new" "${'$'}V/${'$'}b" || fail "install ${'$'}b failed"
        |  done
        |  for o in "${'$'}D"/v-*; do [ "${'$'}o" = "${'$'}V" ] || rm -rf "${'$'}o"; done
        |  echo "INSTALLED=1" ;;
        |dns)
        |  DEV=${'$'}(sysdev); [ -n "${'$'}DEV" ] || fail "no ${'$'}SYS in ${'$'}MOUNTS"
        |  { echo "${'$'}MARK"
        |    for k in net.dns1 net.dns2; do v=${'$'}(getprop ${'$'}k); [ -n "${'$'}v" ] && [ "${'$'}v" != 1.1.1.1 ] && echo "nameserver ${'$'}v"; done
        |    echo "nameserver 1.1.1.1"; } > "${'$'}D/resolv.conf"
        |  [ -f "${'$'}CONF" ] && ! ourconf && cp "${'$'}CONF" "${'$'}D/resolv.conf.orig"
        |  mount -o remount,rw "${'$'}DEV" "${'$'}SYS"
        |  cp "${'$'}D/resolv.conf" "${'$'}CONF" && chmod 644 "${'$'}CONF" && chown 0:0 "${'$'}CONF"
        |  mount -o remount,ro "${'$'}DEV" "${'$'}SYS"
        |  ourconf || fail "could not write ${'$'}CONF"
        |  echo "DNS=1" ;;
        |start)
        |  detach
        |  echo "${'$'}2" > "${'$'}D/session"
        |  if running; then echo "RUNNING=1"; exit 0; fi
        |  mkdir -p "${'$'}D/state"; rm -f "${'$'}SOCK"
        |  ( setsid nohup "${'$'}D/v-${'$'}1/tailscaled" --tun=userspace-networking --statedir="${'$'}D/state" --socket="${'$'}SOCK" --port=0 > "${'$'}D/tailscaled.log" 2>&1 < /dev/null & echo ${'$'}! > "${'$'}D/tailscaled.pid" )
        |  i=0; while [ ${'$'}i -lt 15 ] && [ ! -e "${'$'}SOCK" ]; do sleep 1; i=${'$'}((i+1)); done
        |  sleep 2
        |  running && [ -e "${'$'}SOCK" ] || fail "tailscaled did not start: ${'$'}(lastline "${'$'}D/tailscaled.log")"
        |  echo "RUNNING=1" ;;
        |up)
        |  TS="${'$'}D/v-${'$'}1/tailscale"; A=""
        |  if [ -n "${'$'}4" ]; then cp "${'$'}4" "${'$'}D/authkey" && chmod 600 "${'$'}D/authkey" && A="--authkey=file:${'$'}D/authkey" || fail "auth key copy failed"; fi
        |  "${'$'}TS" --socket="${'$'}SOCK" up --login-server="${'$'}2" ${'$'}A --hostname="${'$'}3" --accept-dns=false --accept-routes=false --timeout=60s > "${'$'}D/up.log" 2>&1
        |  R=${'$'}?
        |  rm -f "${'$'}D/authkey"
        |  [ ${'$'}R -eq 0 ] || fail "${'$'}(lastline "${'$'}D/up.log")"
        |  IP=${'$'}("${'$'}TS" --socket="${'$'}SOCK" ip -4 2>/dev/null)
        |  [ -n "${'$'}IP" ] || fail "no tailnet address"
        |  echo "IP=${'$'}IP" ;;
        |adb-on)
        |  detach
        |  KEY=${'$'}(cat "${'$'}1"); [ -n "${'$'}KEY" ] || fail "no adb key"
        |  if ! haskey "${'$'}KEY"; then
        |    if [ -f "${'$'}KEYS" ]; then
        |      # Rewritten through cat so the file keeps its owner and mode.
        |      { while IFS= read -r l || [ -n "${'$'}l" ]; do echo "${'$'}l"; done < "${'$'}KEYS"; echo "${'$'}KEY"; } > "${'$'}D/adb_keys.new"
        |      cat "${'$'}D/adb_keys.new" > "${'$'}KEYS"; rm -f "${'$'}D/adb_keys.new"
        |    else
        |      echo "${'$'}KEY" > "${'$'}KEYS"; chown 1000:2000 "${'$'}KEYS"; chmod 640 "${'$'}KEYS"
        |    fi
        |    haskey "${'$'}KEY" || fail "could not add the adb key"
        |    echo "${'$'}KEY" > "${'$'}D/adbkey"; : > "${'$'}D/key-added"
        |  fi
        |  CUR=${'$'}(getprop service.adb.tcp.port)
        |  if [ "${'$'}CUR" != 5555 ]; then
        |    [ -f "${'$'}D/adb-tcp-prev" ] || echo "${'$'}CUR" > "${'$'}D/adb-tcp-prev"
        |    adbrestart 5555
        |  fi
        |  echo "ADB=1" ;;
        |stop)
        |  detach
        |  TS=""; for t in "${'$'}D"/v-*/tailscale; do [ -x "${'$'}t" ] && TS="${'$'}t"; done
        |  if [ -n "${'$'}TS" ] && running; then
        |    "${'$'}TS" --socket="${'$'}SOCK" logout > /dev/null 2>&1 &
        |    LP=${'$'}!; i=0
        |    while kill -0 ${'$'}LP 2>/dev/null && [ ${'$'}i -lt 15 ]; do sleep 1; i=${'$'}((i+1)); done
        |    kill ${'$'}LP 2>/dev/null
        |  fi
        |  for p in /proc/[0-9]*; do n=${'$'}{p#/proc/}; ours "${'$'}n" && kill "${'$'}n" 2>/dev/null; done
        |  sleep 1
        |  for p in /proc/[0-9]*; do n=${'$'}{p#/proc/}; ours "${'$'}n" && kill -9 "${'$'}n" 2>/dev/null; done
        |  rm -rf "${'$'}D/state" "${'$'}SOCK" "${'$'}D/tailscaled.pid" "${'$'}D/session" "${'$'}D/authkey"
        |  if [ -f "${'$'}D/key-added" ] && [ -f "${'$'}D/adbkey" ] && [ -f "${'$'}KEYS" ]; then
        |    KEY=${'$'}(cat "${'$'}D/adbkey")
        |    { while IFS= read -r l || [ -n "${'$'}l" ]; do [ "${'$'}l" = "${'$'}KEY" ] || echo "${'$'}l"; done < "${'$'}KEYS"; } > "${'$'}D/adb_keys.new"
        |    cat "${'$'}D/adb_keys.new" > "${'$'}KEYS"; rm -f "${'$'}D/adb_keys.new"
        |  fi
        |  rm -f "${'$'}D/key-added" "${'$'}D/adbkey"
        |  if ourconf; then
        |    DEV=${'$'}(sysdev)
        |    mount -o remount,rw "${'$'}DEV" "${'$'}SYS"
        |    if [ -f "${'$'}D/resolv.conf.orig" ]; then cp "${'$'}D/resolv.conf.orig" "${'$'}CONF"; chmod 644 "${'$'}CONF"; else rm -f "${'$'}CONF"; fi
        |    mount -o remount,ro "${'$'}DEV" "${'$'}SYS"
        |  fi
        |  rm -f "${'$'}D/resolv.conf" "${'$'}D/resolv.conf.orig"
        |  if [ -f "${'$'}D/adb-tcp-prev" ]; then
        |    PREV=${'$'}(cat "${'$'}D/adb-tcp-prev"); rm -f "${'$'}D/adb-tcp-prev"
        |    adbrestart "${'$'}{PREV:--1}"
        |  fi
        |  echo "STOPPED=1" ;;
        |status)
        |  [ -f "${'$'}D/session" ] && echo "SESSION=${'$'}(cat "${'$'}D/session")"
        |  running && echo "RUNNING=1"
        |  exit 0 ;;
        |purge)
        |  case "${'$'}D" in */kinframe-ts) ;; *) fail "unexpected directory ${'$'}D" ;; esac
        |  [ -f "${'$'}D/session" ] && fail "a session is running"
        |  running && fail "tailscaled is running"
        |  rm -rf "${'$'}D"
        |  echo "PURGED=1" ;;
        |*) fail "unknown command ${'$'}CMD" ;;
        |esac
        |""".trimMargin()

    /** `KEY=value` lines of a command's output. */
    fun values(output: String): Map<String, String> = output.lineSequence()
        .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
        .filter { (k, _) -> k.isNotEmpty() && k.all { it.isUpperCase() || it == '_' } }
        .toMap()
}
