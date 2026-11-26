# Third-Party Notices

DuoIME is licensed under the BSD-3-Clause License (see `LICENSE`).
This file preserves copyright notices and license texts for upstream open-source components
that DuoIME ports, adapts, or integrates under Option A (100% permissive repository & default binaries).

---

## ShellDeck (`ime-core`, `feedback/`, `Sketch*` ports)

- Upstream: https://github.com/xxb/ShellDeck
- License: MIT (`SPDX-License-Identifier: MIT`)
- Scope:
  - Pure-Kotlin IME core (`:engine-lite` in M1) ported with original MIT headers preserved.
  - Procedural keystroke sound DSP, polyphonic `AudioTrack` mixer, pitch/band router, and capability-tiered haptics engine (`keyboard-ui/src/main/kotlin/dev/duoime/ui/feedback/*`) ported and adapted to `:core` `FeedbackKey` with original MIT headers preserved.
  - Whiteboard geometry/physics components (planned M3) ported with original MIT headers preserved.

### Bundled `:engine-lite` Permissive Dictionary Datasets

The compact offline dictionaries packaged under `engine-lite/src/main/resources/duoime/dict/` (`core_dict.tsv`, `en_words.tsv`, `s2t.tsv`) are derived from the following permissive open-source datasets via ShellDeck:

- **mozillazg/pinyin-data** (https://github.com/mozillazg/pinyin-data)
  - Copyright (c) mozillazg and `pinyin-data` contributors
  - License: MIT (`SPDX-License-Identifier: MIT`; see MIT License text below)
  - Scope: Mandarin character-to-pinyin mappings (`core_dict.tsv`)
- **mozillazg/phrase-pinyin-data** (https://github.com/mozillazg/phrase-pinyin-data)
  - Copyright (c) mozillazg and `phrase-pinyin-data` contributors
  - License: MIT (`SPDX-License-Identifier: MIT`; see MIT License text below)
  - Scope: Mandarin phrase-to-pinyin mappings (`core_dict.tsv`)
- **fxsjy/jieba** (https://github.com/fxsjy/jieba)
  - Copyright (c) 2012 Sun Junyi (fxsjy)
  - License: MIT (`SPDX-License-Identifier: MIT`; see MIT License text below)
  - Scope: Chinese word frequency statistics (`core_dict.tsv`)
- **Wolf Garbe / SymSpell** (https://github.com/wolfgarbe/SymSpell)
  - Copyright (c) Wolf Garbe (`SymSpell` contributors; SCOWL + Google Ngram frequency dictionary)
  - License: MIT (`SPDX-License-Identifier: MIT`; see MIT License text below)
  - Scope: Compact English frequency lexicon (`en_words.tsv`)
- **BYVoid/OpenCC** (https://github.com/BYVoid/OpenCC)
  - Copyright (c) 2010-2024 Carbo Kuo (BYVoid) and OpenCC contributors
  - License: Apache License, Version 2.0 (`SPDX-License-Identifier: Apache-2.0`; full license text included in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt))
  - Scope: `STCharacters.txt` and `STPhrases.txt` Simplified-to-Traditional mappings (`s2t.tsv`)

```text
MIT License

Copyright (c) 2026 ShellDeck Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## YuyanIme / yuyansdk (Kotlin handwriting pipeline helpers, planned M3)

- Upstream: https://github.com/gurecn/YuyanIme and https://github.com/gurecn/yuyansdk
- License: BSD-3-Clause (`SPDX-License-Identifier: BSD-3-Clause`)
- Scope: Only BSD-3-Clause Kotlin helpers (e.g., handwriting preprocessing/segmentation/CTC decoding in M3). Proprietary prebuilt `.so` binaries (`libyuyanime.so`, `libyuyanwriting.so`), `rec.nb`, `pinyin4j`, and `assets/pinyindb/` are explicitly excluded from DuoIME.

```text
Copyright (c) 2026, 王莹
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## Planned Permissive Dependencies (M2–M4)

As native engines and optional models are integrated in subsequent milestones, their exact
upstream commit/version pins and license notices (such as `librime` BSD-3-Clause, `librime-lua` BSD-3-Clause,
`Lua` MIT, `OpenCC` Apache-2.0 NOTICE, `marisa-trie` BSD-2-Clause, `leveldb` BSD-3-Clause,
`yaml-cpp` MIT, `Boost` BSL-1.0, `onnxruntime-android` MIT, and `PP-OCRv5` Apache-2.0)
will be recorded here alongside the in-app Open-Source Licenses screen.

Optional GPL-3.0 dictionaries (such as `rime-ice` / `rime-frost`) and non-permissive voice weights
(such as `SenseVoice`) are never committed to this repository or bundled in default binaries;
they are strictly user-initiated downloads or local SAF imports with upfront license notices.
