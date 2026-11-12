/* copies the web app that tools/build.py made (../www) next to main.js, where electron-builder packs it */
const fs=require("fs"),path=require("path");
const from=path.join(__dirname,"..","www"),to=path.join(__dirname,"www");
if(!fs.existsSync(path.join(from,"index.html")))throw new Error("../www/index.html is missing: run python3 tools/build.py first");
fs.rmSync(to,{recursive:true,force:true});fs.cpSync(from,to,{recursive:true});
console.log("www copied");
