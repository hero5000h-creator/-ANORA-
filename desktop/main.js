/* أنورا · ANORA for Windows.
   The same web app as the Android build (../src/app.html, built into www/ by tools/build.py) runs in an Electron
   window. The few phone-only native pieces it calls through Capacitor have a desktop stand-in here:
     - Filesystem + FileTransfer: surah downloads for listening offline, kept in the user's AppData folder;
     - LocalNotifications: prayer and adhkar reminders as Windows notifications (while the app is open or minimised).
   Everything else (media notification, widgets, the memorisation mic, volume keys) is Android-only and the app
   hides or explains it on Windows. */
"use strict";
const {app,BrowserWindow,protocol,ipcMain,shell,screen,nativeTheme,Notification,session,net}=require("electron");
const path=require("path"),fs=require("fs"),fsp=require("fs/promises"),{Readable}=require("stream");

const APP_ID="app.anora.quran";
const WWW=path.join(__dirname,"www");
const ICON=path.join(__dirname,"build","icon.ico");
app.setAppUserModelId(APP_ID);
/* one window only: a second launch brings the first one forward */
if(!app.requestSingleInstanceLock()){app.quit();return}

/* the page is served from app://anora/ (a fixed origin, so its saved settings and bookmarks stay put);
   downloaded files are under app://anora/_files/<dir>/... */
protocol.registerSchemesAsPrivileged([{scheme:"app",privileges:{standard:true,secure:true,supportFetchAPI:true,stream:true,corsEnabled:true}}]);
const FILES=()=>path.join(app.getPath("userData"),"files");
const DIRS={DATA:"data",CACHE:"cache",DOCUMENTS:"data",LIBRARY:"data"};
const fileOf=(directory,p)=>{const base=path.join(FILES(),DIRS[directory]||"data"),f=path.resolve(base,String(p||"").replace(/^\/+/,""));
  if(f!==base&&!f.startsWith(base+path.sep))throw new Error("bad path");return f};
const uriOf=(directory,p)=>`app://anora/_files/${DIRS[directory]||"data"}/${String(p||"").replace(/^\/+/,"").split(/[\\/]/).map(encodeURIComponent).join("/")}`;
/* app://anora/_files/data/audio/x.mp3 -> the file on disk (also accepts a bare relative path) */
function uriToFile(u){const m=/^app:\/\/anora\/_files\/([a-z]+)\/(.*)$/.exec(String(u||""));if(!m)throw new Error("bad uri");
  const dir=Object.keys(DIRS).find(k=>DIRS[k]===m[1])||"DATA";return fileOf(dir,decodeURIComponent(m[2]))}

const TYPES={".html":"text/html; charset=utf-8",".js":"text/javascript; charset=utf-8",".json":"application/json; charset=utf-8",".css":"text/css; charset=utf-8",
  ".woff2":"font/woff2",".woff":"font/woff",".ttf":"font/ttf",".otf":"font/otf",".png":"image/png",".jpg":"image/jpeg",".svg":"image/svg+xml",".mp3":"audio/mpeg",".webp":"image/webp"};
/* serves a file with Range support: the recitation seeks inside surah files, which needs 206 replies */
async function serve(file,req){
  let st;try{st=await fsp.stat(file);if(!st.isFile())throw 0}catch(e){return new Response("not found",{status:404})}
  const type=TYPES[path.extname(file).toLowerCase()]||"application/octet-stream",size=st.size;
  const h={"content-type":type,"accept-ranges":"bytes","cache-control":"no-cache"};
  const r=/bytes=(\d*)-(\d*)/.exec(req.headers.get("range")||"");
  if(r&&size){let a=r[1]===""?Math.max(0,size-(+r[2]||0)):+r[1],b=r[1]===""||r[2]===""?size-1:Math.min(size-1,+r[2]);
    if(a>b||a>=size)return new Response(null,{status:416,headers:{...h,"content-range":`bytes */${size}`}});
    return new Response(Readable.toWeb(fs.createReadStream(file,{start:a,end:b})),{status:206,headers:{...h,"content-length":String(b-a+1),"content-range":`bytes ${a}-${b}/${size}`}})}
  return new Response(Readable.toWeb(fs.createReadStream(file)),{status:200,headers:{...h,"content-length":String(size)}})}
function onApp(req){
  const u=new URL(req.url);let p=decodeURIComponent(u.pathname).replace(/^\/+/,"");
  if(u.host!=="anora")return new Response("not found",{status:404});
  if(p.startsWith("_files/")){try{return serve(uriToFile(`app://anora/${u.pathname.replace(/^\/+/,"")}`),req)}catch(e){return new Response("bad path",{status:400})}}
  if(!p)p="index.html";
  const f=path.resolve(WWW,p);if(!f.startsWith(WWW+path.sep))return new Response("bad path",{status:400});
  return serve(f,req)}

/* ---------- window size and place are kept between runs ---------- */
const STATE=()=>path.join(app.getPath("userData"),"window.json");
function loadState(){try{return JSON.parse(fs.readFileSync(STATE(),"utf8"))}catch(e){return {}}}
function saveState(w){try{if(w.isDestroyed())return;const s={...loadState(),maximized:w.isMaximized(),zoom:w.webContents.getZoomLevel()};
  if(!w.isMaximized()&&!w.isMinimized()&&!w.isFullScreen())s.bounds=w.getBounds();fs.writeFileSync(STATE(),JSON.stringify(s))}catch(e){}}
/* a saved place that is no longer on any screen (a monitor was unplugged) is ignored */
function onScreen(b){return b&&screen.getAllDisplays().some(d=>{const a=d.workArea;return b.x<a.x+a.width-80&&b.x+b.width>a.x+80&&b.y>=a.y-8&&b.y<a.y+a.height-60})}

let win=null;
function createWindow(){
  const st=loadState(),wa=screen.getPrimaryDisplay().workAreaSize;
  /* first run: a comfortable two-page size, centred */
  const def={width:Math.min(1400,Math.round(wa.width*.86)),height:Math.min(940,Math.round(wa.height*.9))};
  const b=onScreen(st.bounds)?st.bounds:def;
  win=new BrowserWindow({...b,minWidth:480,minHeight:560,show:false,title:"أنورا",icon:ICON,backgroundColor:"#12291f",autoHideMenuBar:true,
    webPreferences:{preload:path.join(__dirname,"preload.js"),contextIsolation:true,sandbox:true,nodeIntegration:false,spellcheck:false,
      /* the recitation keeps its ayah steps going while the window is minimised */
      backgroundThrottling:false}});
  win.setMenu(null);
  if(st.maximized)win.maximize();
  win.once("ready-to-show",()=>{win.show();if(st.zoom)win.webContents.setZoomLevel(st.zoom)});
  let t=null;const later=()=>{clearTimeout(t);t=setTimeout(()=>saveState(win),400)};
  ["resize","move","maximize","unmaximize"].forEach(e=>win.on(e,later));
  win.on("close",()=>saveState(win));
  win.on("enter-full-screen",()=>send("Window","full",{on:true}));
  win.on("leave-full-screen",()=>send("Window","full",{on:false}));
  /* Windows keys that a browser would handle: F11 full screen, Ctrl + / - / 0 zoom the whole window */
  win.webContents.on("before-input-event",(e,i)=>{if(i.type!=="keyDown")return;const c=i.control||i.meta;
    if(i.key==="F11"){e.preventDefault();win.setFullScreen(!win.isFullScreen());return}
    if(c&&(i.key==="="||i.key==="+"||i.code==="NumpadAdd")){e.preventDefault();zoomBy(.5)}
    else if(c&&(i.key==="-"||i.code==="NumpadSubtract")){e.preventDefault();zoomBy(-.5)}
    else if(c&&(i.key==="0"||i.code==="Numpad0")){e.preventDefault();win.webContents.setZoomLevel(0);saveState(win)}
    else if(c&&i.shift&&i.key.toLowerCase()==="i"&&!app.isPackaged)win.webContents.toggleDevTools()});
  /* links (report a mistake, sources, licences) open in the default browser, never inside the app */
  win.webContents.setWindowOpenHandler(({url})=>{if(/^https?:|^mailto:/.test(url))shell.openExternal(url);return {action:"deny"}});
  win.webContents.on("will-navigate",(e,url)=>{if(!url.startsWith("app://anora/")){e.preventDefault();if(/^https?:|^mailto:/.test(url))shell.openExternal(url)}});
  win.loadURL("app://anora/index.html");
}
function zoomBy(d){const z=Math.max(-3,Math.min(4,win.webContents.getZoomLevel()+d));win.webContents.setZoomLevel(z);saveState(win)}
const send=(pl,ev,data)=>{try{if(win&&!win.isDestroyed())win.webContents.send("cap-ev",pl,ev,data)}catch(e){}};

/* ---------- the Capacitor stand-ins ---------- */
const H={};
H.Filesystem={
  async getUri({path:p,directory}){return {uri:uriOf(directory,p)}},
  async mkdir({path:p,directory}){await fsp.mkdir(fileOf(directory,p),{recursive:true});return {}},
  async rmdir({path:p,directory}){await fsp.rm(fileOf(directory,p),{recursive:true,force:true});return {}},
  async deleteFile({path:p,directory}){await fsp.rm(fileOf(directory,p),{force:true});return {}},
  async writeFile({path:p,directory,data,encoding}){const f=fileOf(directory,p);await fsp.mkdir(path.dirname(f),{recursive:true});
    await fsp.writeFile(f,encoding?String(data):Buffer.from(String(data),"base64"));return {uri:uriOf(directory,p)}},
  async stat({path:p,directory}){const s=await fsp.stat(fileOf(directory,p));return {type:s.isDirectory()?"directory":"file",size:s.size,mtime:s.mtimeMs,uri:uriOf(directory,p)}}};
H.FileTransfer={
  /* downloads to a .part file first, so a cut connection never leaves half a surah that looks complete */
  async downloadFile({url,path:to,progress}){
    if(!/^https:\/\//.test(String(url)))throw new Error("only https downloads");
    const f=uriToFile(to),part=f+".part";await fsp.mkdir(path.dirname(f),{recursive:true});
    const r=await net.fetch(url);if(!r.ok||!r.body)throw new Error("HTTP "+r.status);
    const len=+r.headers.get("content-length")||0,out=fs.createWriteStream(part);let got=0,last=0;
    try{for await(const chunk of Readable.fromWeb(r.body)){got+=chunk.length;if(!out.write(chunk))await new Promise(ok=>out.once("drain",ok));
        const n=Date.now();if(progress&&n-last>200){last=n;send("FileTransfer","progress",{url,bytes:got,contentLength:len,lengthComputable:len>0})}}
      await new Promise((ok,no)=>out.end(e=>e?no(e):ok()))}
    catch(e){out.destroy();await fsp.rm(part,{force:true});throw e}
    if(len&&got<len){await fsp.rm(part,{force:true});throw new Error("incomplete")}
    if(progress)send("FileTransfer","progress",{url,bytes:got,contentLength:len||got,lengthComputable:true});
    await fsp.rename(part,f);return {path:to}}};
/* reminders: timers in this process, shown as Windows notifications. They fire while the app runs (minimised is
   fine); the page schedules them again every time it opens. */
const timers=new Map();
const MAXT=2147483647;
function fireAt(id,when,fn){clearTimeout(timers.get(id));const d=when-Date.now();if(d<-60000)return;
  timers.set(id,setTimeout(()=>{if(when-Date.now()>1000)fireAt(id,when,fn);else fn()},Math.max(0,Math.min(MAXT,d))))}
function nextDaily(h,m){const t=new Date();t.setHours(h,m,0,0);if(t.getTime()<=Date.now()+1000)t.setDate(t.getDate()+1);return t.getTime()}
function show(n){
  if(!Notification.isSupported())return;
  const x=new Notification({title:String(n.title||"أنورا"),body:String(n.body||""),icon:ICON,silent:false});
  x.on("click",()=>{if(win){if(win.isMinimized())win.restore();win.show();win.focus()}
    send("LocalNotifications","localNotificationActionPerformed",{actionId:"tap",notification:{id:n.id,title:n.title,body:n.body,extra:n.extra||null}})});
  x.show()}
H.LocalNotifications={
  async checkPermissions(){return {display:Notification.isSupported()?"granted":"denied"}},
  async requestPermissions(){return {display:Notification.isSupported()?"granted":"denied"}},
  async createChannel(){return {}},
  async schedule({notifications}){for(const n of notifications||[]){const s=n.schedule||{};
      if(s.on&&s.on.hour!=null){const run=()=>{show(n);fireAt(n.id,nextDaily(s.on.hour,s.on.minute||0),run)};fireAt(n.id,nextDaily(s.on.hour,s.on.minute||0),run)}
      else if(s.at){fireAt(n.id,new Date(s.at).getTime(),()=>{timers.delete(n.id);show(n)})}
      else show(n)}
    return {notifications:(notifications||[]).map(n=>({id:n.id}))}},
  async cancel({notifications}){for(const n of notifications||[]){clearTimeout(timers.get(n.id));timers.delete(n.id)}return {}},
  async getPending(){return {notifications:[...timers.keys()].map(id=>({id}))}}};
/* the frame's light/dark follows the paper colour chosen in the app */
H.Window={
  async theme({dark}){nativeTheme.themeSource=dark?"dark":"light";return {}},
  async title({text}){if(win&&text)win.setTitle(String(text).slice(0,200));return {}},
  async full({on}){if(win)win.setFullScreen(on==null?!win.isFullScreen():!!on);return {on:win?win.isFullScreen():false}},
  async zoom({d}){if(win){if(d===0)win.webContents.setZoomLevel(0);else zoomBy(d>0?.5:-.5)}return {}}};
ipcMain.handle("cap",async(e,pl,m,json)=>{
  if(!win||e.sender!==win.webContents)throw new Error("not allowed");
  const fn=H[pl]&&H[pl][m];if(!fn)throw new Error(`${pl}.${m} is not available on Windows`);
  return fn(json?JSON.parse(json):{})});

app.on("second-instance",()=>{if(win){if(win.isMinimized())win.restore();win.show();win.focus()}});
app.whenReady().then(()=>{
  protocol.handle("app",onApp);
  /* notifications and location (prayer times) are asked by the app itself; nothing else is granted */
  session.defaultSession.setPermissionRequestHandler((wc,perm,cb)=>cb(["notifications","geolocation","fullscreen","clipboard-sanitized-write","media"].includes(perm)));
  createWindow()});
app.on("window-all-closed",()=>app.quit());
