/* Smoke test run on every push before the APK is built. It catches the kind of mistake that reached a
   test build once: recitation that stops right after it starts. Run: node tests/smoke.js (after npm run build) */
const {chromium}=require("playwright");const fs=require("fs"),path=require("path"),http=require("http");
const WWW=path.join(__dirname,"..","www"),TONE=fs.readFileSync(path.join(__dirname,"tone.mp3"));
const src=fs.readFileSync(path.join(WWW,"index.html"),"utf8"),hook='recUI();audData(reciter).then(d=>{if(!aud)aud=d});';
if(!src.includes(hook))throw new Error("test hook point not found in www/index.html");
fs.writeFileSync(path.join(WWW,"smoke.html"),src.replace(hook,hook+"window.__smk={playFrom,P:()=>player,AU,setPlaying,SST,openAyah};"));
const types={".html":"text/html",".js":"text/javascript",".json":"application/json",".woff2":"font/woff2",".ttf":"font/ttf",".css":"text/css"};
const srv=http.createServer((q,r)=>{const f=path.join(WWW,decodeURIComponent(q.url.split("?")[0]));
  fs.readFile(f,(e,b)=>{if(e){r.writeHead(404);r.end();return}r.writeHead(200,{"content-type":types[path.extname(f)]||"application/octet-stream"});r.end(b)})}).listen(0);
const fails=[];const ok=(c,m)=>{console.log((c?"ok   ":"FAIL ")+m);if(!c)fails.push(m)};
/* general layout check: every open panel or toast is on screen, actually on top (not hidden behind the page),
   and a toast never sits on the Quran text */
const LAYOUT=(want)=>{const out=[],vw=innerWidth,vh=innerHeight,vis=e=>e&&!e.hidden&&getComputedStyle(e).display!=="none"&&e.getBoundingClientRect().width>0;
  for(const id of ["prefs","repBox","srcBox","sheet"]){const e=document.getElementById(id);if(!vis(e))continue;const r=e.getBoundingClientRect();
    if(r.left<-1||r.top<-1||r.right>vw+1||r.bottom>vh+1)out.push(id+" off screen");
    const x=Math.min(vw-2,Math.max(1,r.left+r.width/2)),y=Math.min(vh-2,Math.max(1,r.top+Math.min(40,r.height/2)));const h=document.elementFromPoint(x,y);
    if(!h||!e.contains(h))out.push(id+" covered by "+(h?(h.id||h.className||h.tagName):"nothing"))}
  const t=document.getElementById("toast");if(t&&t.classList.contains("on")){const r=t.getBoundingClientRect();
    if([...document.querySelectorAll("#book .pg [data-a],#flow [data-a]")].some(w=>{const q=w.getBoundingClientRect();return q.width&&q.right>r.left&&q.left<r.right&&q.bottom>r.top+2&&q.top<r.bottom-2}))out.push("toast over Quran text")}
  for(const id of want||[]){const e=document.getElementById(id);if(!vis(e)&&!(id==="toast"&&e&&e.classList.contains("on")))out.push(id+" expected but not showing")}
  return out};
const lay=async(p,m,want)=>{const o=await p.evaluate(LAYOUT,want||[]);ok(!o.length,`${m}: layout clean ${o.join(", ")}`)};
(async()=>{const port=srv.address().port;
  const b=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:["--autoplay-policy=no-user-gesture-required"]});
  for(const [name,vp] of [["phone",{width:412,height:891}],["fold",{width:1000,height:760}]]){
    const ctx=await b.newContext({viewport:vp,hasTouch:true,isMobile:true});const p=await ctx.newPage();const errs=[];
    p.on("pageerror",e=>errs.push(e.message));
    await p.addInitScript(()=>{localStorage.setItem("mushaf.last","50");localStorage.setItem("mushaf.hintN","9");localStorage.setItem("mushaf.easyQ","1");window.__L={};
      window.Capacitor={isNativePlatform:()=>true,getPlatform:()=>"android",convertFileSrc:u=>u,addListener:(pl,ev,fn)=>{window.__L[pl+ev]=fn},
        nativePromise:(pl,m,o)=>m==="getUri"?Promise.resolve({uri:"file:///d/"+o.path}):Promise.resolve({})}});
    await p.route(/\.mp3(\?|$)/,r=>{const h=r.request().headers().range,B=TONE;if(h){const m=/bytes=(\d+)-(\d*)/.exec(h),a=+m[1],e=m[2]?+m[2]:B.length-1;
      return r.fulfill({status:206,headers:{"content-type":"audio/mpeg","accept-ranges":"bytes","content-range":`bytes ${a}-${e}/${B.length}`},body:B.subarray(a,e+1)})}
      r.fulfill({status:200,headers:{"content-type":"audio/mpeg","accept-ranges":"bytes"},body:B})});
    await p.goto(`http://localhost:${port}/smoke.html`);await p.waitForTimeout(4000);
    ok(!errs.length,`${name}: no script errors on load ${errs.join(" | ")}`);
    const lock=await p.evaluate(()=>document.getElementById("qLockMsg").textContent);ok(/مطابق/.test(lock),`${name}: Quran text lock matches`);
    await p.evaluate(()=>__smk.playFrom(__smk.SST[2]+3));await p.waitForTimeout(3000);
    let s=await p.evaluate(()=>({on:__smk.P().on,paused:__smk.AU.paused,t:__smk.AU.currentTime}));
    ok(s.on&&!s.paused,`${name}: recitation keeps playing 3s after start (on=${s.on} paused=${s.paused})`);
    const t1=s.t;await p.waitForTimeout(1200);s=await p.evaluate(()=>({t:__smk.AU.currentTime}));ok(s.t>t1+.5,`${name}: audio time moves (${t1.toFixed(1)} → ${s.t.toFixed(1)})`);
    await p.evaluate(()=>window.__L.Playbackaction&&window.__L.Playbackaction({a:"pause"}));await p.waitForTimeout(300);
    ok(await p.evaluate(()=>__smk.AU.paused&&!__smk.P().on),`${name}: notification pause pauses`);
    await p.evaluate(()=>window.__L.Playbackaction&&window.__L.Playbackaction({a:"play"}));await p.waitForTimeout(800);
    ok(await p.evaluate(()=>!__smk.AU.paused&&__smk.P().on),`${name}: notification play resumes`);
    await p.evaluate(()=>document.getElementById("pRep").click());await p.waitForTimeout(700);await lay(p,`${name}: repeat panel`,["repBox"]);
    /* tapping an ayah sets the range end; tapping before "from" keeps the range within 300 */
    {const r=await p.evaluate(async()=>{const S=__smk.SST[2];const tap=async id=>{const w=[...document.querySelectorAll('#book .pg [data-a="'+id+'"]')].map(e=>e.getBoundingClientRect()).find(q=>q.width&&q.top>0&&q.bottom<innerHeight);
        if(!w)return false;const o={bubbles:true,clientX:w.left+w.width/2,clientY:w.top+w.height/2,pointerId:9,isPrimary:true};document.elementFromPoint(o.clientX,o.clientY).dispatchEvent(new PointerEvent("pointerdown",o));await new Promise(z=>setTimeout(z,150));return true};
      const t=await tap(S+6),to=document.getElementById("rbTo").textContent,open=!document.getElementById("repBox").hidden;return {t,to,open}});
      ok(r.t&&r.open&&/٧/.test(r.to),`${name}: tapping an ayah sets the repeat end (${r.to})`)}
    await p.evaluate(()=>document.getElementById("rbX").click());await p.waitForTimeout(400);
    await p.evaluate(()=>document.getElementById("pStop").click());await p.waitForTimeout(300);
    await p.evaluate(()=>__smk.openAyah(__smk.SST[2]+3));await p.waitForTimeout(1500);await lay(p,`${name}: ayah sheet`,["sheet"]);
    ok(await p.evaluate(()=>!document.getElementById("sheet").hidden&&/الآية ٤/.test(document.getElementById("shMeta").textContent)),`${name}: ayah sheet opens on the right ayah`);
    ok(await p.evaluate(()=>/الآيتين ٣–٤/.test((document.querySelector(".tfg")||{}).textContent||"")),`${name}: grouped tafsir is labelled`);
    await p.keyboard.press("Escape");await p.waitForTimeout(600);
    /* opening an ayah that is not on screen turns to its page */
    {const r=await p.evaluate(async()=>{__smk.openAyah(__smk.SST[2]+40);await new Promise(z=>setTimeout(z,1200));return document.getElementById("wMeta").textContent});
      ok(/٥٥/.test(r),`${name}: ayah off screen turns to its page (${r})`);await p.keyboard.press("Escape");await p.waitForTimeout(500);
      await p.evaluate(()=>__smk.openAyah(__smk.SST[2]+3));await p.waitForTimeout(900);await p.keyboard.press("Escape");await p.waitForTimeout(600)}
    const pg0=await p.evaluate(()=>document.getElementById("wMeta").textContent);await p.keyboard.press("ArrowLeft");await p.waitForTimeout(1400);
    ok(await p.evaluate(()=>document.getElementById("wMeta").textContent)!==pg0,`${name}: page turns`);
    
    await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(500);
    for(const z of ["1","1.3"]){await p.evaluate(z=>document.querySelector(`[data-uz="${z}"]`).click(),z);await p.waitForTimeout(400);
      const r=await p.evaluate(()=>{const r=document.getElementById("prefs").getBoundingClientRect();return [r.top,r.bottom]});
      ok(r[0]>=-1&&r[1]<=vp.height+1,`${name}: settings panel on screen at text size ${z} (${r.map(Math.round)})`);await lay(p,`${name}: settings at text size ${z}`,["prefs"])}
    await p.keyboard.press("Escape");await p.evaluate(()=>{document.getElementById("easyBox").hidden=false;document.getElementById("easyY").click()});await p.waitForTimeout(700);await lay(p,`${name}: toast after "yes"`);await p.waitForTimeout(800);
    await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(600);await lay(p,`${name}: settings over large print`,["prefs"]);
    await p.evaluate(()=>document.getElementById("prefsX")&&document.getElementById("fPrefs").click());await p.keyboard.press("Escape");await p.waitForTimeout(400);
    if(name==="phone"){
      const r=await p.evaluate(()=>{const b=document.querySelector("#app>.bar").getBoundingClientRect(),f=document.querySelector(".flbar");return f&&!document.getElementById("flow").hidden?[b.bottom,f.getBoundingClientRect().top]:[1,-1]});
      ok(r[1]>=r[0]-1,`${name}: large-print toolbar starts below the top bar after "yes" (${r.map(Math.round)})`)}
    /* reciting from memory: the Android plugin's words reveal the hidden words in order */
    {await p.keyboard.press("Escape");await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(400);
      await p.evaluate(()=>{const b=document.getElementById("bFlow");if(b.getAttribute("aria-pressed")==="true")b.click()});await p.waitForTimeout(300);
      await p.evaluate(()=>document.getElementById("bMemo").click());await p.waitForTimeout(900);
      const r=await p.evaluate(async()=>{const nx=()=>document.querySelector("#book .pg .nx");const a=nx();if(!a)return {err:"no cursor"};
        document.getElementById("mMic").click();await new Promise(z=>setTimeout(z,200));
        const t=a.textContent+" "+(a.nextElementSibling&&a.nextElementSibling.nextElementSibling?a.nextElementSibling.nextElementSibling.textContent:"");
        window.__L.Speechheard&&window.__L.Speechheard({text:t,final:true});await new Promise(z=>setTimeout(z,300));
        return {on:document.getElementById("mMic").classList.contains("on"),moved:nx()!==a,shown:!a.classList.contains("hid")}});
      ok(r.on&&r.moved&&r.shown,`${name}: reciting by voice reveals the words heard ${JSON.stringify(r)}`)}
    await ctx.close()}
  await b.close();srv.close();fs.unlinkSync(path.join(WWW,"smoke.html"));
  if(fails.length){console.log(`\n${fails.length} check(s) failed`);process.exit(1)}console.log("\nall checks passed")})().catch(e=>{console.error(e);srv.close();process.exit(1)});
