// Runs every HnLink test: npm install once, then `npm test` (or `node run_all.js`) in this folder.
// compile_test compiles the WHOLE main.lua (catches syntax errors and Lua's 200-locals limit, which only a real
// compiler sees); count_locals reports how close the main chunk is to that limit; the *_test.js files each cut one
// part out of main.lua and run it against mocks of Hello Neighbor / UE4SS.
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const dir = __dirname;
const tests = ["compile_test.js", "count_locals.js",
  ...fs.readdirSync(dir).filter(f => f.endsWith("_test.js") && f !== "compile_test.js").sort()];
let failed = 0;
for (const t of tests) {
  let out;
  try {
    out = execFileSync(process.execPath, [path.join(dir, t)], { cwd: dir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (e) {
    out = (e.stdout || "") + (e.stderr || "");
    failed++;
  }
  const last = out.split("\n").filter(l => l && !l.startsWith("LOG ")).pop() || "(no output)";
  console.log(`${t.padEnd(18)} ${last}`);
}
for (const f of ["crumb_test.txt"]) { try { fs.unlinkSync(path.join(dir, f)); } catch {} }
if (failed) { console.log(`\n${failed} test(s) FAILED`); process.exit(1); }
console.log("\nall passed");
