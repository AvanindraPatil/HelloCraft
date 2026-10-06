// Compiles the WHOLE main.lua with a real Lua compiler (limits like 200 locals are only caught here, not by luaparse).
const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(src)) !== 0) { console.log("COMPILE FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
console.log("main.lua compiles");
