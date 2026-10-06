const luaparse = require("luaparse");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const ast = luaparse.parse(src, {luaVersion: "5.3", locations: true});
let n = 0; const rows = [];
for (const s of ast.body) {
  if (s.type === "LocalStatement") { for (const v of s.variables) { n++; rows.push(s.loc.start.line + " " + v.name); } }
  else if (s.type === "FunctionDeclaration" && s.isLocal) { n++; rows.push(s.loc.start.line + " " + s.identifier.name); }
}
console.log("top-level locals:", n);
if (process.argv[2]) console.log(rows.join("\n"));
