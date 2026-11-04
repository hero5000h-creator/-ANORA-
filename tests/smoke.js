/* Smoke test run on every push before the APK is built. It catches the kind of mistake that reached a
   test build once: recitation that stops right after it starts. Run: node tests/smoke.js (after npm run build) */
const {chromium}=require("playwright");const fs=require("fs"),path=require("path"),http=require("http");
const WWW=path.join(__dirname,"..","www"),TONE=fs.readFileSync(path.join(__dirname,"tone.mp3"));
const src=fs.readFileSync(path.join(WWW,"index.html"),"utf8"),hook='recUI();audData(reciter).then(d=>{if(!aud)aud=d});';
if(!src.includes(hook))throw new Error("test hook point not found in www/index.html");
fs.writeFileSync(path.join(WWW,"smoke.html"),src.replace(hook,hook+"window.__smk={playFrom,P:()=>player,aud:()=>aud,AU,setPlaying,SST,openAyah,nativeAudio:!(AU instanceof HTMLMediaElement),qY,AW,NA,setRate,turn:{rasterPage,setupFlip,render,finish,cur:()=>cur,mode:()=>mode,W:()=>W}};"));
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
    const fl=document.getElementById("flow"),flOn=vis(fl);/* the book lies hidden under the flow text while it is open */
    if([...document.querySelectorAll(flOn?"#flow [data-a]":"#book .pg [data-a]")].some(w=>{const q=w.getBoundingClientRect();return q.width&&q.right>r.left&&q.left<r.right&&q.bottom>r.top+2&&q.top<r.bottom-2}))out.push("toast over Quran text")}
  for(const id of want||[]){const e=document.getElementById(id);if(!vis(e)&&!(id==="toast"&&e&&e.classList.contains("on")))out.push(id+" expected but not showing")}
  return out};
const lay=async(p,m,want)=>{const o=await p.evaluate(LAYOUT,want||[]);ok(!o.length,`${m}: layout clean ${o.join(", ")}`)};
(async()=>{const port=srv.address().port;
  const b=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:["--autoplay-policy=no-user-gesture-required"]});
  for(const [name,vp] of [["phone",{width:412,height:891}],["fold",{width:1000,height:760}]]){
    const ctx=await b.newContext({viewport:vp,hasTouch:true,isMobile:true});const p=await ctx.newPage();const errs=[];
    p.on("pageerror",e=>errs.push(e.message));
    await p.addInitScript(()=>{localStorage.setItem("mushaf.last","50");localStorage.setItem("mushaf.hintN","9");localStorage.setItem("mushaf.easyQ","1");window.__L={};
      /* the native player (AudioPlugin.kt) stood in for by an <audio> that speaks the plugin's events */
      const MA=new Audio(),em=(e,d)=>{const f=window.__L["Audio"+e];f&&f(d||{})},tm=()=>em("time",{ms:MA.currentTime*1000,playing:!MA.paused&&!MA.ended});
      setInterval(()=>{if(!MA.paused)tm()},200);MA.addEventListener("playing",tm);MA.addEventListener("pause",tm);MA.addEventListener("seeked",tm);
      MA.addEventListener("ended",()=>em("ended"));MA.addEventListener("loadedmetadata",()=>em("loaded",{dur:MA.duration*1000}));MA.addEventListener("error",()=>em("error",{msg:"x"}));window.__MA=MA;
      const AP={load:o=>{MA.src=o.url},play:()=>MA.play(),pause:()=>MA.pause(),seek:o=>{MA.currentTime=o.ms/1000},rate:o=>{MA.playbackRate=o.rate},stop:()=>{MA.pause();MA.removeAttribute("src")}};
      window.Capacitor={isNativePlatform:()=>true,getPlatform:()=>"android",convertFileSrc:u=>u,isPluginAvailable:n=>n==="Audio",addListener:(pl,ev,fn)=>{window.__L[pl+ev]=fn},
        nativePromise:(pl,m,o)=>pl==="Widget"?Promise.resolve(m==="update"?(window.__wg=o,{}):m==="take"?(()=>{const g=window.__wgGo||null;window.__wgGo=null;return {go:g}})():{ok:false}):pl==="Audio"?Promise.resolve().then(()=>AP[m](o||{})).then(()=>({})):pl==="Playback"&&m==="ack"?(window.__ack=o.n,Promise.resolve({})):m==="getUri"?Promise.resolve({uri:"file:///d/"+o.path}):Promise.resolve({})}});
    await p.route(/\.mp3(\?|$)/,r=>{const h=r.request().headers().range,B=TONE;if(h){const m=/bytes=(\d+)-(\d*)/.exec(h),a=+m[1],e=m[2]?+m[2]:B.length-1;
      return r.fulfill({status:206,headers:{"content-type":"audio/mpeg","accept-ranges":"bytes","content-range":`bytes ${a}-${e}/${B.length}`},body:B.subarray(a,e+1)})}
      r.fulfill({status:200,headers:{"content-type":"audio/mpeg","accept-ranges":"bytes"},body:B})});
    await p.goto(`http://localhost:${port}/smoke.html`);await p.waitForTimeout(4000);
    /* page turn: the page images for the curl must render (an invalid SVG once made every turn a flat fold),
       the curl / 3D spread must be the one used, and every turn style must move exactly one page (spread: two) */
    {const r=await p.evaluate(async()=>{const T=__smk.turn,c0=T.cur();const img=await T.rasterPage(c0);
        const step=T.mode()==="spread"?2:1;const f=T.setupFlip("fwd",c0+step);const kind=f.b3?"3d":f.curl?"curl":"flat";T.finish(f,false);return {img:!!img,kind,c0,step}});
      ok(r.img,`${name}: page image for the curl renders`);ok(r.kind===(name==="fold"?"3d":"curl"),`${name}: turn uses the ${name==="fold"?"3D spread":"curl"} (${r.kind})`);
      const bb=await p.locator("#book").boundingBox(),y=bb.y+bb.height*.6;
      for(const st of ["curl","slide","none"]){await p.evaluate(s=>localStorage.setItem("mushaf.turn",s),st);
        const c0=await p.evaluate(()=>__smk.turn.cur());
        await p.mouse.move(bb.x+bb.width*.2,y);await p.mouse.down();for(let k=1;k<=10;k++){await p.mouse.move(bb.x+bb.width*(.2+k*.06),y);await p.waitForTimeout(16)}await p.mouse.up();await p.waitForTimeout(1100);
        const c1=await p.evaluate(()=>__smk.turn.cur());ok(c1===c0+r.step,`${name}: swipe turns one ${r.step>1?"spread":"page"} with "${st}" (${c0}→${c1})`);
        await p.evaluate(()=>{document.dispatchEvent(new KeyboardEvent("keydown",{key:"ArrowRight"}))});await p.waitForTimeout(1100)}
      await p.evaluate(()=>localStorage.removeItem("mushaf.turn"))}
    ok(!errs.length,`${name}: no script errors on load ${errs.join(" | ")}`);
    /* home-screen widgets: the page sends what they show, and a tap on one opens the screen it names */
    {const w=await p.evaluate(()=>window.__wg||null);
      ok(w&&w.page>=1&&w.sur&&w.cats&&w.cats.sab&&w.cats.sab.of>0&&w.cats.sab.f0&&w.cats.sab.f0.t&&Array.isArray(w.pt)&&w.free.length,`${name}: widget data sent (page ${w&&w.page}, ${w&&w.sur}, ${w&&w.cats&&w.cats.sab&&w.cats.sab.of} morning adhkar)`);
      const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));window.__wgGo="az:mas";window.__L.Widgetopen({});await W(900);
        const az=!document.getElementById("az").hidden,tt=document.getElementById("azTitle").textContent;window.__wgGo="page:77";window.__L.Widgetopen({});await W(1600);
        return {az,tt,closed:document.getElementById("az").hidden,cur:__smk.turn.cur()}});
      ok(r.az&&r.tt==="أذكار المساء"&&r.closed&&(r.cur===77||r.cur===76),`${name}: widget taps open the evening adhkar, then page 77 (${JSON.stringify(r)})`)}
    ok(await p.evaluate(()=>__smk.nativeAudio),`${name}: Android uses the native player`);
    const lock=await p.evaluate(()=>document.getElementById("qLockMsg").textContent);ok(/مطابق/.test(lock),`${name}: Quran text lock matches`);
    {const r=await p.evaluate(()=>{const S=__smk;let bad=[];for(let id=1;id<=S.NA&&bad.length<3;id++)for(const w of S.AW[id]){const q=S.qY(w);if(/ي[\u064B-\u065F\u0670\u06D6-\u06ED]*$/.test(q))bad.push(q)}
      return {bad,a:S.qY("ٱلَّذِيٓ"),b:S.qY("فِيٓ"),c:S.qY("فِيهِ")}});
      ok(!r.bad.length&&r.a==="ٱلَّذِىٓ"&&r.b==="فِىٓ"&&r.c==="فِيهِ",`${name}: final yā' is dotless everywhere, also before maddah (${r.bad.join(" ")||"ok"})`)}
    await p.evaluate(()=>__smk.playFrom(__smk.SST[2]+3));await p.waitForTimeout(3000);
    let s=await p.evaluate(()=>({on:__smk.P().on,paused:__smk.AU.paused,t:__smk.AU.currentTime}));
    ok(s.on&&!s.paused,`${name}: recitation keeps playing 3s after start (on=${s.on} paused=${s.paused})`);
    const t1=s.t;await p.waitForTimeout(1200);s=await p.evaluate(()=>({t:__smk.AU.currentTime}));ok(s.t>t1+.5,`${name}: audio time moves (${t1.toFixed(1)} → ${s.t.toFixed(1)})`);
    await p.evaluate(()=>window.__L.Playbackaction&&window.__L.Playbackaction({a:"pause"}));await p.waitForTimeout(300);
    ok(await p.evaluate(()=>__smk.AU.paused&&!__smk.P().on),`${name}: notification pause pauses`);
    await p.evaluate(()=>window.__L.Playbackaction&&window.__L.Playbackaction({a:"play"}));await p.waitForTimeout(800);
    ok(await p.evaluate(()=>!__smk.AU.paused&&__smk.P().on),`${name}: notification play resumes`);
    /* the next ayah follows on its own: jump near the end of this ayah and wait */
    {const r=await p.evaluate(async()=>{const id=__smk.P().id,g=__smk.P().seg;__smk.AU.currentTime=(g.end-600)/1000;await new Promise(z=>setTimeout(z,1800));return [id,__smk.P().id,__smk.P().on]});
      ok(r[1]===r[0]+1&&r[2],`${name}: recitation moves on to the next ayah (${r[0]} → ${r[1]})`)}
    /* the page fell behind the player (asleep in the background): it catches up to the ayah being heard, no jump back */
    {const r=await p.evaluate(async()=>{const P=__smk.P(),s=__smk.SST[2],rows=__smk.aud().surahs[3],row=rows.find(x=>x[0]===P.id-s+2);if(!row)return ["no row"];window.__id0=P.id;
        P.sk=0;const W=z=>new Promise(r=>setTimeout(r,z));__smk.AU.currentTime=(row[1]+400)/1000;P.sk=0;await W(1500);return [P.id-s+1,row[0],P.on]});
      ok(r[0]===r[1]&&r[2],`${name}: page catches up with the player after sleeping (${r})`)}
    /* a late notification "next" (the service already did it) is not done twice; a fresh one is confirmed */
    {const r=await p.evaluate(async()=>{const P=__smk.P(),id=P.id,W=z=>new Promise(r=>setTimeout(r,z));window.__ack=0;
        window.__L.Playbackaction({a:"next",n:5,t:Date.now()-5000});await W(300);const late=P.id;
        window.__L.Playbackaction({a:"next",n:6,t:Date.now()});await W(300);const out=[id,late,P.id,window.__ack];__smk.playFrom(window.__id0);await W(1200);return out});
      ok(r[1]===r[0]&&r[2]===r[0]+1&&r[3]===6,`${name}: late notification "next" ignored, fresh one done and confirmed (${r})`)}
    /* the system pausing the player (headphones unplugged, a call) pauses the app too */
    {const r=await p.evaluate(async()=>{window.__L.Audiohalt&&window.__L.Audiohalt({reason:3});await new Promise(z=>setTimeout(z,300));const off=!__smk.P().on;__smk.setPlaying(true);await new Promise(z=>setTimeout(z,800));return [off,__smk.P().on&&!__smk.AU.paused]});
      ok(r[0]&&r[1],`${name}: a system pause pauses the app, and play resumes (${r})`)}
    await p.evaluate(()=>document.getElementById("pRep").click());await p.waitForTimeout(700);await lay(p,`${name}: repeat panel`,["repBox"]);
    /* tapping an ayah sets the range end; tapping before "from" keeps the range within 300 */
    {const r=await p.evaluate(async()=>{const S=__smk.SST[2];const tap=async id=>{const w=[...document.querySelectorAll('#book .pg [data-a="'+id+'"]')].map(e=>e.getBoundingClientRect()).find(q=>{if(!(q.width&&q.top>0&&q.bottom<innerHeight))return false;const h=document.elementFromPoint(q.left+q.width/2,q.top+q.height/2);return h&&h.closest("#book")});
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
    /* the visible listen button starts the recitation on this page; a removed mark can be put back */
    {await p.keyboard.press("Escape");await p.waitForTimeout(300);
      const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));document.getElementById("fListen").click();await W(1500);const on=__smk.P().on;
        document.getElementById("pStop").click();await W(300);__smk.openAyah(__smk.SST[2]+3);await W(700);const m=document.getElementById("aMark");
        m.click();await W(100);m.click();await W(100);const removed=m.getAttribute("aria-pressed")==="false";const u=document.querySelector("#toast .tundo");u&&u.click();await W(200);
        return {on,removed,undo:!!u,back:m.getAttribute("aria-pressed")==="true"}});
      ok(r.on,`${name}: "استمع" starts the recitation`);ok(r.removed&&r.undo&&r.back,`${name}: undo puts a removed mark back ${JSON.stringify(r)}`);
      await p.evaluate(()=>{document.getElementById("aMark").click()});await p.keyboard.press("Escape");await p.waitForTimeout(500)}
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
    if(name==="phone"){/* prayer times are computed on the device: Baghdad, Ja'fari, a fixed day, in Baghdad time */
      const r=await p.evaluate(()=>{const c=new adhan.Coordinates(33.3152,44.3661),d=new Date(Date.UTC(2026,10,4,9));
        const f=t=>["fajr","sunrise","dhuhr","asr","maghrib","isha"].map(k=>t[k].toLocaleTimeString("en-GB",{timeZone:"Asia/Baghdad",hour:"2-digit",minute:"2-digit"}));
        return {j:f(new adhan.PrayerTimes(c,d,new adhan.CalculationParameters("Other",16,14,0,4))),m:f(new adhan.PrayerTimes(c,d,adhan.CalculationMethod.MuslimWorldLeague()))}});
      const inOrder=a=>a.every((x,i)=>!i||x>a[i-1]);
      ok(inOrder(r.j)&&inOrder(r.m)&&r.m[0]>="04:45"&&r.m[0]<="05:15"&&r.m[4]>="16:55"&&r.m[4]<="17:20"&&r.j[4]>r.m[4],`${name}: prayer times for Baghdad look right ${JSON.stringify(r)}`)}
    {/* المتشابهات: the switch colours the twin phrases on the open page (page 51, built next to the open page 50, holds 3:10 and 3:11 from the huffaz' list) and takes them off again */
      await p.evaluate(()=>document.getElementById("bMut").click());await p.waitForTimeout(1800);
      const on=await p.evaluate(()=>[...new Set([...document.querySelectorAll('.pg[data-p="51"] .ms')].map(w=>+w.dataset.a))]);
      await p.evaluate(()=>document.getElementById("bMut").click());await p.waitForTimeout(800);
      const off=await p.evaluate(()=>document.querySelectorAll(".pg .ms,.pg .md,#flBody .ms,#flBody .md").length);
      ok(on.length>=2&&!off,`${name}: similar phrases coloured on the page and cleared when switched off (ayat ${on.join(",")}; ${off} left)`)}
    await ctx.close()}
  await b.close();srv.close();fs.unlinkSync(path.join(WWW,"smoke.html"));
  if(fails.length){console.log(`\n${fails.length} check(s) failed`);process.exit(1)}console.log("\nall checks passed")})().catch(e=>{console.error(e);srv.close();process.exit(1)});
