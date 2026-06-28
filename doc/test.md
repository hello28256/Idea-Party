# Adversarial Claim Verification: Node.js v22.22.0 Symlink Permission Change

## Research Question
What changed in the Node.js permission model between v20 and v22?

## Claim Under Review

> "Node.js v22.22.0 requires full read and write permissions to symlink APIs when permission model is active"

**Source:** https://raw.githubusercontent.com/nodejs/node/main/doc/changelogs/CHANGELOG_V22.md (primary)
**Supporting quote:** "require full read and write to symlink APIs"

---

## Evidence Gathered

### 1. Primary Source Evidence (from Node.js v22.22.0 CHANGELOG)

**v22.22.0 changelog entry (January 13, 2026):**
- `(CVE-2025-55130) require full read and write to symlink APIs`
- Commit: `**(CVE-2025-55130)** **lib,permission**: require full read and write to symlink APIs`

**Related security fixes in v22.22.0:**
- `(CVE-2025-55132) disable futimes when permission model is enabled`

**Follow-up permission fixes in v22.22.2 (March 24, 2026):**
- `(CVE-2026-21715) add permission check to realpath.native`
- `(CVE-2026-21716) include permission check on lib/fs/promises`

### 2. Context: Node.js Permission Model

The `--experimental-permission` flag (introduced in Node.js v20.x) allows granular file system access control via CLI flags:
- `--allow-fs-read` — grants read access to specific paths
- `--allow-fs-write` — grants write access to specific paths

When the permission model is active, operations are blocked unless explicitly allowed.

### 3. CVE Context

CVE-2025-55130 is a security vulnerability related to symlink handling under the permission model, indicating that the previous behavior allowed symlink operations to bypass or circumvent permission checks improperly.

---

## Checklist Analysis

### 1. Is the claim supported by the quote, or is it an overreach/misread?

**SUPPORTED with minor inference.** The changelog quote is: `require full read and write to symlink APIs`. The claim restates this as: "requires full read and write permissions to symlink APIs when permission model is active."

The phrase "when permission model is active" is not explicitly in the quote but is a reasonable inference because:
- The commit category is `lib,permission` (confirmed by commit message)
- CVE-2025-55130 is a security fix specifically addressing the permission model
- The changelog is specifically about the permission model behavior

The phrase "full read and write" in the quote supports the interpretation that **both** read and write permissions are required (not just one) when using symlink APIs under the permission model.

### 2. WebSearch for contradicting evidence

WebSearch encountered API errors on follow-up queries. However, no contradicting evidence was found in available search results. The official changelog is authoritative for this type of claim.

### 3. Is the source quality sufficient for the claim's strength?

**High quality.** The source is the official Node.js GitHub repository's changelog (primary source). The CVE identifier adds credibility as it indicates a security-related change. The commit category `lib,permission` confirms the change relates to the permission system.

### 4. Is the claim outdated?

**No.** v22.22.0 was released January 13, 2026. The current date context (May 2026) makes this approximately 5 months old — well within the "current" window for fast-moving Node.js security releases.

### 5. Is this a marketing claim / press release / cherry-picked benchmark?

**No.** This is an official Node.js security changelog entry describing a CVE fix.

---

## Verdict

| Dimension | Assessment |
|---|---|
| v22.22.0 changes symlink API behavior under permission model | **SUPPORTED** — confirmed by CHANGELOG entry |
| Requires full read and write permissions | **SUPPORTED** — "require full read and write to symlink APIs" is explicit |
| When permission model is active | **SUPPORTED (inference)** — commit is categorized under `lib,permission` and addresses a CVE in the permission system |

**refuted = false**

The claim is accurate and well-supported. The changelog explicitly states that symlink APIs now require full read and write permissions under the permission model. The CVE context confirms this is a security fix addressing improper permission handling for symlink operations.

---

## Sources

- [Node.js CHANGELOG_V22.md](https://raw.githubusercontent.com/nodejs/node/main/doc/changelogs/CHANGELOG_V22.md)
- [Node.js CVE-2025-55130](https://nodejs.org/en/blog/vulnerability/cve-2025-55130)
