# Rabin–Karp Search Engine

Java backend and HTML/CSS/JavaScript dashboard. No external libraries required.

## IntelliJ setup
1. Create a Java project with JDK 17 or newer.
2. Put main.java and index.html inside src.
3. Set Run > Edit Configurations > Working directory to the project root.
4. Run the main class, then open http://localhost:8080.
Stop your KMP server first if it is using port 8080.

Alternatively, open a terminal in this extracted folder:
```
javac main.java
java main
```
Open the localhost address, not index.html directly.

## Features
- Manual input and multiple TXT, MD, CSV and LOG uploads.
- Case-sensitive or simple case-insensitive matching.
- Optional overlapping matches, highlights, zero-based indices, one-based line and column.
- Pattern hash, hash parameters, windows checked, hash candidates and rejected collisions.
- Edit sources with no matches, download UTF-8 copies, or apply drafts and search again.
- Original uploaded files stay unchanged.

## Algorithm
A polynomial rolling hash uses base 257 and modulus 1,000,000,007.
The pattern hash is computed once per request. Each source begins with a window of pattern length.
The next hash removes the outgoing character, multiplies by the base and adds the incoming character.
Every equal-hash candidate is verified character by character, so collisions cannot create false matches.

Expected search cost with few hash candidates is O(N + M). Worst case is O(NM), including many equal-hash windows or dense true matches requiring full verification. This is not KMP's guaranteed linear bound.
Rolling-hash state is O(1), excluding input, result storage and optional case-normalized text.
Comparisons count verification character comparisons only. Windows checked excludes starts skipped in non-overlap mode.
Line/column reporting scans forward once per source. Offsets use UTF-16 code units.
Case-insensitive mode uses length-preserving character lowercase conversion, not full Unicode case folding.
The application loads files into memory rather than streaming them.

## Connection
The browser sends JSON to POST /api/search. main.java runs Rabin–Karp and responds with results and metrics.
Editing and downloading happen in the browser.

