import { run as clangFormat } from '@cpp-linter/clang-format';
import { globSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const mode = process.argv[2];
if (process.argv.length !== 3 || !['--write', '--check'].includes(mode)) {
  throw new Error('Use npm run format or npm run format:check');
}

process.chdir(fileURLToPath(new URL('..', import.meta.url)));
const sources = globSync(['world-mirror-*/src/**/*.java', 'scripts/*.java']).sort();
const temporary = mkdtempSync(join(tmpdir(), 'world-mirror-toolkit-format-'));
try {
  const list = join(temporary, 'sources.txt');
  writeFileSync(list, sources.join('\n'));
  process.exitCode = clangFormat([
    '--style=file:.clang-format',
    '--Werror',
    `--files=${list}`,
    mode === '--write' ? '-i' : '--dry-run',
  ]);
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
