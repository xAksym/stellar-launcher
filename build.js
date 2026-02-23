#!/usr/bin/env node
/**
 * StellarLauncher - Node.js Build Script
 *
 * Użycie:
 *   node build.js          - kompiluje i pakuje fat JAR
 *   node build.js clean    - czyści katalog out/
 *   node build.js run      - kompiluje i uruchamia
 *   node build.js exe      - kompiluje i tworzy StellarLauncher.exe (Windows)
 */

const { spawnSync } = require('child_process');
const fs   = require('fs');
const path = require('path');

// ─── Konfiguracja ────────────────────────────────────────────────────────────

const CONFIG = {
    srcDir:      'src',
    outDir:      'out',
    libsDir:     'libs',
    jarName:     'StellarLauncher.jar',
    mainClass:   'pl.stellarlauncher.StellarLauncher',
    appName:     'StellarLauncher',
    appVersion:  '1.0.0',
    javaVersion: '21',
    encoding:    'UTF-8',
    exeDir:      'dist',
};

// ─── Helpers ─────────────────────────────────────────────────────────────────

const sep   = process.platform === 'win32' ? ';' : ':';
const isWin = process.platform === 'win32';

function log(msg)  { console.log(`\x1b[36m[build]\x1b[0m ${msg}`); }
function ok(msg)   { console.log(`\x1b[32m[  ok ]\x1b[0m ${msg}`); }
function err(msg)  { console.error(`\x1b[31m[ err ]\x1b[0m ${msg}`); }
function step(msg) { console.log(`\n\x1b[33m══ ${msg} ══\x1b[0m`); }

function run(cmd, opts = {}) {
    log(`$ ${cmd}`);
    const result = spawnSync(cmd, { shell: true, stdio: 'inherit', ...opts });
    if (result.status !== 0) {
        err(`Komenda zakończona kodem: ${result.status}`);
        process.exit(result.status ?? 1);
    }
}

function findFiles(dir, ext) {
    const results = [];
    if (!fs.existsSync(dir)) return results;
    function walk(d) {
        for (const f of fs.readdirSync(d)) {
            const full = path.join(d, f);
            if (fs.statSync(full).isDirectory()) walk(full);
            else if (f.endsWith(ext)) results.push(full);
        }
    }
    walk(dir);
    return results;
}

function findLibs() {
    if (!fs.existsSync(CONFIG.libsDir)) {
        err(`Brak folderu ${CONFIG.libsDir}/ z plikami .jar (gson, jna)`);
        process.exit(1);
    }
    const jars = findFiles(CONFIG.libsDir, '.jar');
    if (jars.length === 0) {
        err(`Folder ${CONFIG.libsDir}/ jest pusty!`);
        process.exit(1);
    }
    return jars;
}

function detectJava() {
    const javaHome = process.env.JAVA_HOME;
    const bin = javaHome ? path.join(javaHome, 'bin') : null;
    const q = (tool) => bin ? `"${path.join(bin, tool)}"` : tool;
    return {
        javac:    q(isWin ? 'javac.exe'    : 'javac'),
        jar:      q(isWin ? 'jar.exe'      : 'jar'),
        java:     q(isWin ? 'java.exe'     : 'java'),
        jpackage: q(isWin ? 'jpackage.exe' : 'jpackage'),
    };
}

// ─── Kroki budowania ─────────────────────────────────────────────────────────

function clean() {
    step('Clean');
    if (fs.existsSync(CONFIG.outDir)) {
        fs.rmSync(CONFIG.outDir, { recursive: true });
        ok(`Usunięto ${CONFIG.outDir}/`);
    }
    if (fs.existsSync(CONFIG.jarName)) {
        fs.rmSync(CONFIG.jarName);
        ok(`Usunięto ${CONFIG.jarName}`);
    }
    if (fs.existsSync(CONFIG.exeDir)) {
        fs.rmSync(CONFIG.exeDir, { recursive: true });
        ok(`Usunięto ${CONFIG.exeDir}/`);
    }
}

function compile() {
    step('Compile');
    fs.mkdirSync(CONFIG.outDir, { recursive: true });

    const libs = findLibs();
    const cp   = libs.join(sep);
    const srcs = findFiles(CONFIG.srcDir, '.java');
    const { javac } = detectJava();

    if (srcs.length === 0) {
        err(`Brak plikow .java w ${CONFIG.srcDir}/`);
        process.exit(1);
    }
    log(`Znaleziono ${srcs.length} plikow .java`);
    fs.writeFileSync('sources.txt', srcs.join('\n'));

    run(`${javac} -encoding ${CONFIG.encoding} -source ${CONFIG.javaVersion} -target ${CONFIG.javaVersion} -cp "${cp}" -d ${CONFIG.outDir} @sources.txt`);
    ok('Kompilacja zakonczona sukcesem');
}

function extractJars() {
    step('Extract dependencies');
    const libs = findLibs();
    const { jar } = detectJava();
    const cwd = path.resolve(CONFIG.outDir);

    for (const lib of libs) {
        log(`Rozpakowuje: ${path.basename(lib)}`);
        spawnSync(`${jar} xf "${path.resolve(lib)}"`, { shell: true, cwd, stdio: 'inherit' });
    }
    ok('Zaleznosci wypakowane');
}

function packageJar() {
    step('Package JAR');
    const { jar } = detectJava();

    const manifestDir  = path.join(CONFIG.outDir, 'META-INF');
    const manifestFile = path.join(manifestDir, 'MANIFEST.MF');
    fs.mkdirSync(manifestDir, { recursive: true });
    fs.writeFileSync(manifestFile, [
        'Manifest-Version: 1.0',
        `Main-Class: ${CONFIG.mainClass}`,
        '',
    ].join('\n'));

    run(`${jar} cfm "${CONFIG.jarName}" "${manifestFile}" -C "${CONFIG.outDir}" .`);
    const size = (fs.statSync(CONFIG.jarName).size / 1024).toFixed(0);
    ok(`Gotowy JAR: ${CONFIG.jarName} (${size} KB)`);
}

function buildExe() {
    step('Build EXE (jpackage)');

    if (!isWin) {
        err('jpackage tworzy .exe tylko na Windowsie!');
        err('Uruchom: node build.js exe   na swoim komputerze z Windows.');
        process.exit(1);
    }

    if (!fs.existsSync(CONFIG.jarName)) {
        err(`Brak ${CONFIG.jarName} — najpierw zbuilduj JAR: node build.js`);
        process.exit(1);
    }

    const { jpackage } = detectJava();
    fs.mkdirSync(CONFIG.exeDir, { recursive: true });

    // Opcjonalna ikona — jesli masz icon.ico w folderze projektu
    const iconFlag = fs.existsSync('icon.ico') ? `--icon icon.ico` : '';

    // --type app-image = folder z .exe bez instalatora (najszybszy)
    // Zmien na --type msi dla instalatora (wymaga WiX Toolset)
    const cmd = [
        jpackage,
        `--type app-image`,
        `--input .`,
        `--main-jar ${CONFIG.jarName}`,
        `--main-class ${CONFIG.mainClass}`,
        `--name ${CONFIG.appName}`,
        `--app-version ${CONFIG.appVersion}`,
        `--dest ${CONFIG.exeDir}`,
        iconFlag,
        `--java-options "-Xmx4g"`,
        `--java-options "-Xms512m"`,
        `--java-options "-XX:+UseG1GC"`,
    ].filter(Boolean).join(' ');

    run(cmd);

    const exePath = path.join(CONFIG.exeDir, CONFIG.appName, `${CONFIG.appName}.exe`);
    if (fs.existsSync(exePath)) {
        ok(`Gotowy EXE: ${exePath}`);
    } else {
        ok(`Folder aplikacji: ${path.join(CONFIG.exeDir, CONFIG.appName)}/`);
    }

    console.log(`\nWskazowka: zeby stworzyc instalator MSI zamiast folderu:`);
    console.log(`  Zmien "--type app-image" na "--type msi" w build.js`);
    console.log(`  (wymaga WiX Toolset: https://wixtoolset.org)\n`);
}

function runJar() {
    step('Run');
    const { java } = detectJava();
    if (!fs.existsSync(CONFIG.jarName)) {
        err(`Brak ${CONFIG.jarName} — najpierw skompiluj: node build.js`);
        process.exit(1);
    }
    run(`${java} -jar "${CONFIG.jarName}"`);
}

// ─── Main ─────────────────────────────────────────────────────────────────────

const cmd = process.argv[2] || 'build';

console.log(`\n\x1b[35m╔══════════════════════════════╗`);
console.log(`║  StellarLauncher Build Tool  ║`);
console.log(`╚══════════════════════════════╝\x1b[0m\n`);

switch (cmd) {
    case 'clean':
        clean();
        break;
    case 'run':
        clean();
        compile();
        extractJars();
        packageJar();
        runJar();
        break;
    case 'exe':
        clean();
        compile();
        extractJars();
        packageJar();
        buildExe();
        break;
    case 'build':
    default:
        clean();
        compile();
        extractJars();
        packageJar();
        console.log(`\n\x1b[32m Build gotowy!\x1b[0m`);
        console.log(`   java -jar ${CONFIG.jarName}   — uruchom`);
        console.log(`   node build.js exe            — zbuduj .exe\n`);
        break;
}