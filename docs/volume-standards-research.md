# Stem-Volume Equation Research — non-US forestry standards
Research date: 2026-07-22
Purpose: source REAL, CITABLE closed-form stem-volume equations (V in m³) for a forestry measurement app.

**HONESTY NOTE:** Every coefficient below was copied from a source I actually fetched; the fetched source URL is named next to it. Where I could not fetch a source that prints the numbers, the entry says **NOT FOUND** and names where the numbers are published so a human can retrieve them. Nothing here is guessed or interpolated.

**Access caveat for this session:** WebFetch was systematically blocked (HTTP 403 / socket reset / auth redirect) for Taylor & Francis (tandfonline), MDPI, SpringerLink, Korea Science (koreascience.or.kr / .kr), and journal.kfs21.or.kr. Reachable hosts that yielded real numbers were GitHub, rdrr.io (R package source), CRAN man pages, Wikipedia, and Silva Fennica. This is why the Nordic set is fully verified while most Korean coefficients are NOT FOUND (published, but their host is unreachable here).

---

## B. NORDIC — Laasasenaho (1982), Finland  ✅ IMPLEMENTABLE NOW

**Primary reference (equations):** Laasasenaho, J. 1982. *Taper curve and volume functions for pine, spruce and birch (Pinus sylvestris, Picea abies, Betula pendula, Betula pubescens).* Communicationes Instituti Forestalis Fenniae 108: 1–74. URN:ISBN:951-40-0589-9.

**Source I actually fetched for the coefficients:** the R package `lmfor` (Lauri Mehtätalo, "Functions for Forest Biometrics"), function `predvol`, which the package documents as predicting "individual tree volumes using the functions of Laasasenaho (1982)".
- Source code (verified, fetched twice): https://raw.githubusercontent.com/cran/lmfor/master/R/lmfor.R  (also https://github.com/cran/lmfor/blob/master/R/lmfor.R)
- Function docs (units + species codes): https://rdrr.io/cran/lmfor/man/predvol.html
- Model-form cross-check (these are Laasasenaho eqs 61.2 = d+h, and 61.3 = d+h+d6): Kangas et al. 2020, Silva Fennica 54(4):10269, https://www.silvafennica.fi/article/10269

**Units / conventions (from predvol docs + code):**
- `d` = DBH in **cm** (over bark, @1.3 m); `h` = total tree height in **m**.
- Output volume is in **litres (dm³)** → divide by 1000 for m³.
- Total stem volume. Bark: Laasasenaho's `v` functions are total stem volume **over bark** (conventional interpretation; the predvol code carries no bark flag — confirm against the original monograph if under-bark is needed).
- Species codes in the implementation: 1 = Scots pine (*Pinus sylvestris*), 2 = Norway spruce (*Picea abies*), 3 = silver birch (*Betula pendula*), 4 = downy birch (*Betula pubescens*, uses the same coefficients as silver birch in this implementation).

### B.1 Volume from DBH + height (the one to use — "Model 2", Laasasenaho eq. 61.2)
Formula (verbatim from lmfor source):

    v = c1 · d^c2 · c3^d · h^c4 · (h − 1.3)^c5      [v in litres, d in cm, h in m]

| Species | c1 | c2 | c3 | c4 | c5 |
|---|---|---|---|---|---|
| Scots pine (Pinus sylvestris) | 0.036089 | 2.01395 | 0.99676 | 2.07025 | −1.07209 |
| Norway spruce (Picea abies) | 0.022927 | 1.91505 | 0.99146 | 2.82541 | −1.53547 |
| Silver birch (Betula pendula) | 0.011197 | 2.10253 | 0.98600 | 3.98519 | −2.65900 |
| Downy birch (Betula pubescens) | 0.011197 | 2.10253 | 0.98600 | 3.98519 | −2.65900 |

Worked check (pine, d=20 cm, h=18 m): v = 0.036089·20^2.01395·0.99676^20·18^2.07025·16.7^(−1.07209) ≈ **274 litres ≈ 0.274 m³** — physically reasonable for a 20 cm / 18 m pine (over bark). Confirms formula wiring + litre units.

### B.2 Volume from DBH only (fallback when height unknown — "Model 1")
Formula (verbatim from lmfor source):

    v = exp( a1 + a2·ln(2 + 1.25·d) − a3·d )        [v in litres, d in cm]

| Species | a1 | a2 | a3 |
|---|---|---|---|
| Scots pine (Pinus sylvestris) | −5.39417 | 3.48060 | 0.039884 |
| Norway spruce (Picea abies) | −5.39934 | 3.46468 | 0.0273199 |
| Silver birch (Betula pendula) | −5.41948 | 3.57630 | 0.0395855 |
| Downy birch (Betula pubescens) | −5.41948 | 3.57630 | 0.0395855 |

**Validity range:** Laasasenaho / lmfor note the d+h model is not intended for very small trees (< ~3 m tall for conifers, < ~4 m for birch), because of the `ln(h−1.3)` term. Otherwise valid across normal Finnish merchantable sizes.

**Status: IMPLEMENTABLE NOW** for pine, spruce, silver birch, downy birch — both d-only and d+h. Coefficients verified against the R source. Cite Laasasenaho (1982) as the equation source and lmfor `predvol` as the reproducible implementation.

---

## A. SOUTH KOREA — NIFoS / KFRI stem-volume  ⚠️ MOSTLY NOT FOUND (hosts unreachable this session)

**What the official Korean volume table actually is (this I DID confirm):** The current national tree-stem volume table — *입목재적·바이오매스 및 임분수확표* (KFS & NIFoS, 2021) — is generated from **Kozak (1988) variable-exponent taper (stem-curve) equations**, and the paper I fetched states explicitly that the Kozak stem-curve "적분이 가능하지 않기 때문에" — **it cannot be analytically integrated**, so official stem volume is obtained by **numerical integration of the taper curve**, not from a simple closed-form V=f(D,H).
- Fetched source: Journal of Climate Change Research 13(3):355 (2022), full-text view https://jccr.re.kr/_common/do.php?a=full&b=12&bidx=2981&aidx=33421 (cites the official table as "KFS and NIFoS, 2021" and the taper basis as "Kozak, 1988").
- Official table landing page (printed table / downloadable file only): NIFoS 입목수간재적표(2019), https://nifos.forest.go.kr/kfsweb/cop/bbs/selectBoardArticle.do?nttId=3145114&bbsId=BBSMSTR_1069

**Implication:** For the *official* NIFoS numbers there is generally **no public closed-form V=aD^bH^c** — it is a taper-integral table. → **NOT FOUND — official printed table only (no public closed-form)** for the official values of every species.

**Independently-fitted closed-form models DO exist in the literature** (form is known and citable), but I could not fetch the numeric coefficients from any reachable host this session (tandfonline / koreascience / MDPI / Springer all blocked). Per the honesty rule I therefore do **not** print numbers. Where each species' coefficients live:

| Species (Korean) | Model form reported in the literature | Where the coefficients are published (retrieve manually) | Status |
|---|---|---|---|
| Pinus densiflora 소나무 | combined-variable **V = a + b·D²·H** (best); DBH-only alt | Kang, Son, et al. 2017, *Forest Science and Technology* 13(2), doi:10.1080/21580103.2017.1315963 | NOT FOUND (coeffs paywall-blocked here) |
| Pinus koraiensis 잣나무 | combined-variable V = a + b·D²·H | same 2017 FST paper | NOT FOUND (coeffs blocked) |
| Larix kaempferi 낙엽송 | compatible taper + closed-form stem volume | Kang et al., *J. Mountain Science* 13, doi:10.1007/s11629-016-4291-x; also 2017 FST paper | NOT FOUND (coeffs blocked) |
| Quercus acutissima 상수리 | variable-exponent taper → volume table | *J. Korean Soc. For. Sci.*, KoreaScience JAKO201928463078832 | NOT FOUND (coeffs blocked) |
| Quercus mongolica 신갈나무 | stem-taper-derived volume | *Forest Science and Technology*, doi:10.1080/21580103.2019.1592785 | NOT FOUND (coeffs blocked) |
| Cryptomeria japonica 삼나무 | simple 6-model comparison, DBH+H | Seo et al., "Stem volume models for Cryptomeria japonica of Jeju Island" (2014/2019); taper: doi:10.1080/21580103.2017.1393018 | NOT FOUND (coeffs blocked) |
| Chamaecyparis obtusa 편백 | traditional regression + ML, DBH+H | MDPI *Forests* 16(8):1228 (2025); new NIFoS volume table | NOT FOUND (coeffs blocked) |
| Pinus rigida 리기다소나무 | (official NIFoS table; taper-integral) | KFS & NIFoS 2021 volume table | NOT FOUND — official printed table only (no public closed-form) |
| Robinia pseudoacacia 아까시 | Kozak stem-profile → volume table | NIFoS stem-volume-table study (Kozak profile) | NOT FOUND — official printed table only (no public closed-form) |

**Net for Korea:** paradigm confirmed (metric m³, taper/volume-function based — see §D), forms known, but **no numeric coefficients verifiable in this session**. To implement, a human must open the 2017 *Forest Science and Technology* paper (3 conifers, combined-variable V=a+bD²H) and the species papers above, or the NIFoS 2021 printed table, from an unblocked network.

---

## C. GERMANY / CENTRAL EUROPE  ⚙️ METHOD IMPLEMENTABLE; species form-factor table NOT FOUND

### C.1 Official national standard: BDAT taper/volume program
The German National Forest Inventory (Bundeswaldinventur) standard for stem volume and assortments is **BDAT** (Kublin, FVA Baden-Württemberg). It is a **spline-based "varying-coefficient" stem-curve model**, not a closed-form equation: it returns diameter at any height, and **solid-wood (Derbholz, ≥7 cm) and section volume, with and without bark**, in m³.
- BDAT 1.0 built for the first federal inventory (BWI I), completed end of 1987, commissioned by BML.
- Reproducible implementation: R package **rBDAT** ("Implementation of BDAT Tree Taper Fortran Functions"), https://rdrr.io/cran/rBDAT/ and https://www.rdocumentation.org/packages/rBDAT/versions/1.0.0/topics/rBDAT-package ; modern successor **TapeS/TapeR**.
- Method paper: Kublin, E. 2003. "Einheitliche Beschreibung der Schaftform – Methoden und Programme – BDATPro." *European Journal of Forest Research* 122, doi:10.1046/j.1439-0337.2003.00183.x. Tool page: FVA-BW https://www.fva-bw.de/daten-tools/tools/das-sorten-volumenprogramm-bdat
- (Sources fetched via search-result summaries; the R-package pages are directly reachable.)
- **Status: IMPLEMENTABLE via the rBDAT / TapeS library** (call the library; do NOT hand-code — there is no short closed form). Best-in-class for German species (spruce, pine, beech, oak, etc.).

### C.2 Simple fallback: form-factor volume  V = g · h · f   ✅ IMPLEMENTABLE (generic f) / species table NOT FOUND
- Formula and definition confirmed from a fetched source: **V = g·h·f**, where g = basal area at breast height (= π/4·(D/100)² m² for D in cm), h = total height (m), f = form factor (Formzahl), defined as f = actual stem volume / volume of the reference cylinder.
  - Fetched: AWF-Wiki, Univ. Göttingen, "Stem shape," http://wiki.awf.forst.uni-goettingen.de/wiki/index.php/Stem_shape
  - Also confirmed formula V=g·h·f in Pommerening, "Basic tree variables / Forestry summary characteristics," https://www.pommerening.org/wiki/images/e/eb/ForestrySummaryCharacteristics.pdf
- **Form-factor magnitudes I could verify (generic, not per-species):** whole-tree form factor is "commonly in the order of magnitude of **0.45–0.55**"; commercial-stem-length form factor "usually in the order of **~0.7**" (AWF-Wiki, fetched). Using f ≈ 0.5 gives a usable first-order German/Central-European volume estimate.
- **Species-specific Formzahl table (e.g. Fichte/Kiefer/Buche/Eiche exact values): NOT FOUND.** The standard printed source is van Laar & Akça, *Forest Mensuration* (Springer, 2007) — I could not fetch a page that prints the per-species numbers, so no species values are reported here.
- **Status: IMPLEMENTABLE as an approximation** with the generic f≈0.5 (0.45–0.55) and the AWF-Wiki citation. For production-grade German volume, use §C.1 BDAT instead.

---

## D. PARADIGM CONFIRMATION — metric m³, not board-foot log rules  ✅ CONFIRMED
Claim: South Korea and European countries express standing/stem volume in **cubic metres (m³)** via **volume/taper functions or national tables**, and do **not** use the North-American board-foot "log rule" (Doyle / Scribner / International) concept.

Evidence actually fetched / surfaced:
- **North-American side (fetched):** Wikipedia, "Volume table," https://en.wikipedia.org/wiki/Volume_table — treats board-foot log rules as the frame and states volume tables there are "based on different log rules such as Scribner, Doyle, and International ¼ in," giving the Doyle rule in **board feet** (Bd.Ft = L·((D−4)/4)²). These are explicitly board-foot, US-centric systems.
- **Korea (fetched):** the official national table (KFS & NIFoS 2021) is built on **Kozak (1988) taper curves integrated to m³** — a metric volume-function system, no board-foot anywhere (Journal of Climate Change Research 13(3):355, fetched above).
- **Nordic (fetched):** Laasasenaho (1982) / lmfor `predvol` output **litres → m³** (§B). Metric volume functions.
- **Germany (fetched/surfaced):** BDAT computes **Derbholz volume in m³**, with/without bark (§C). Metric.
- General mensuration sources note Europe measures log/stem volume in **cubic metres** (e.g. V = D²·L/10⁴ for logs) while Doyle/Scribner/International are the US board-foot rules (web-search summaries of ruraltech.org Briggs ch.2; UT-Extension PB1650; FPL Spelter 2004 "Converting among Log Scaling Methods").
- **Conclusion: CONFIRMED.** Implement Korea + Europe in **m³** using volume/taper functions or national tables. Do not implement Scribner/Doyle/International board-foot rules for these regions.

---

## SUMMARY TABLE — ready to code vs. needs the printed table

| Region / species | Equation available? | Numbers verified from a fetched source? | Status |
|---|---|---|---|
| **Nordic — Scots pine** (Laasasenaho) | Yes: d+h and d-only | **Yes** (lmfor source) | ✅ IMPLEMENTABLE NOW |
| **Nordic — Norway spruce** | Yes: d+h and d-only | **Yes** (lmfor source) | ✅ IMPLEMENTABLE NOW |
| **Nordic — silver birch** | Yes: d+h and d-only | **Yes** (lmfor source) | ✅ IMPLEMENTABLE NOW |
| **Nordic — downy birch** | Yes (=silver birch coeffs) | **Yes** (lmfor source) | ✅ IMPLEMENTABLE NOW |
| **Germany — any species (BDAT)** | Yes, as a library (rBDAT/TapeS) | Library, not closed form | ✅ IMPLEMENTABLE via library |
| **Germany — form-factor V=g·h·f** | Yes, generic f≈0.45–0.55 | Formula+range yes; species f no | ⚠️ APPROX only; species table NOT FOUND |
| **Korea — P. densiflora / koraiensis / L. kaempferi** | Yes: V=a+b·D²·H (Kang 2017) | **No** (host blocked) | ❌ NOT FOUND (coeffs paywalled here) |
| **Korea — Q. acutissima / Q. mongolica / Cryptomeria / Chamaecyparis** | Yes in literature | **No** (host blocked) | ❌ NOT FOUND (coeffs blocked) |
| **Korea — P. rigida / Robinia** | Official taper-integral table | n/a (no closed form) | ❌ NOT FOUND — official printed table only (no public closed-form) |

**Bottom line:** Only the **Nordic / Laasasenaho** set (pine, spruce, birch — both d+h and d-only) is coefficient-verified and codeable right now. **Germany** is codeable via the **BDAT library** (or a rough generic form factor). **Korea's** equation *forms* are known and the paradigm is confirmed metric, but **no Korean numeric coefficients could be verified** from a reachable source this session — they must be lifted manually from the 2017 *Forest Science and Technology* paper (3 conifers) and the NIFoS 2021 table (all species) from an unblocked network.
