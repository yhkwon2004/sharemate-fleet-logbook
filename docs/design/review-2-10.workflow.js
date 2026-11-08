export const meta = {
  name: 'sharemate-2-10-review',
  description: 'Review the merged 2.10 UI (screenshots + diff) from four lenses, then adversarially verify every finding',
  phases: [{ title: 'Review', detail: 'design critique, accessibility, responsiveness, code correctness' }, { title: 'Verify', detail: 'two skeptics per finding' }],
}

const REPO = 'C:/Users/user/Documents/Codex/2026-10-23/ui/outputs/SHAREMATE'
const SHOTS = args.shots   // directory with the fresh screenshots
const LIST = args.shotList // description of each screenshot file
const SPEC = args.spec
const BASE = args.base     // commit before the implementation merge

const COMMON = `
SHAREMATE 2.10 (Kotlin + Jetpack Compose, car app for Hyundai Pleos Android Automotive + phone app) just gained a day/night scene, whitespace-led layouts, motion and responsive screens. Repository: ${REPO} (read-only for you: do NOT edit files). The design spec the implementation followed is below. Fresh screenshots from the emulators are in ${SHOTS}: ${LIST}. Read the images (the Read tool shows them). The implementation diff is 'git diff ${BASE}..HEAD -- app/src/main' (run it with git in ${REPO}; it is large, so read it file by file with 'git diff ${BASE}..HEAD -- app/src/main/java/ai/sharemate/app/<File>.kt').
Report only real, specific problems a user or reviewer would notice, each anchored to a screenshot and/or a file:line, with the fix you recommend. No praise, no generic advice. Severity: high = wrong/unreadable/broken, medium = clearly off the spec or inconsistent, low = polish.
SPEC:
${SPEC}
`
const FINDINGS = { type: 'object', properties: { findings: { type: 'array', items: { type: 'object', properties: {
  title: { type: 'string' }, severity: { type: 'string', enum: ['high', 'medium', 'low'] }, where: { type: 'string', description: 'screenshot file and/or file:line' },
  evidence: { type: 'string' }, fix: { type: 'string' } }, required: ['title', 'severity', 'where', 'evidence', 'fix'] } } }, required: ['findings'] }

const LENSES = [
  { key: 'design', prompt: `${COMMON}\nLens: DESIGN CRITIQUE. Judge hierarchy, whitespace (nothing stretched edge to edge, backdrop breathing, consistent spacing scale), the day scene's brightness and polish (does the sky/city read? are the glass cards and tiles right? anything washed out or muddy?), night consistency, typography, alignment, and car vs phone consistency. Look at every screenshot.` },
  { key: 'a11y', prompt: `${COMMON}\nLens: ACCESSIBILITY AND LEGIBILITY. For each screenshot, find text or UI parts that look low-contrast by day or by night; for colour pairs in the code, recompute WCAG contrast (relative luminance) and flag text under 4.5:1 and UI parts under 3:1. Check touch targets (car >= 56 dp, phone >= 48 dp), reduced-motion handling (every animation must snap or disappear when LocalReduceMotion is true), screen-reader semantics on new interactive elements, and that nothing animates on the driving screen.` },
  { key: 'responsive', prompt: `${COMMON}\nLens: RESPONSIVENESS AND MOTION. From the diff, verify that the car shell uses ProvideLayout/LocalLayout and that panes reflow for COMPACT/MEDIUM/EXPANDED and short windows; the phone for portrait/landscape/tablet and fontScale 1.3 (look for fixed heights, maxLines without ellipsis, hard-coded 20.dp gutters that ignore LocalLayout, Row children without weight that overflow). Verify the motion spec: pressable on the listed targets, animatedValue on numbers, ring sweep, CountBadge, stagger, pageSwap, scene cross-fade; flag anything that would animate continuously or every second (e.g. a value keyed on the per-second clock), and animations without a reduced-motion fallback.` },
  { key: 'code', prompt: `${COMMON}\nLens: CODE CORRECTNESS. Read the diff for bugs: recomposition churn (LocalScene read inside remember keys, animateFloatAsState keyed on the clock), colours read inside draw lambdas without hoisting, state that resets on recomposition, removed features or copy, broken semantics, lint ModifierParameter violations, duplicated helpers, dead code, unit tests that no longer test what they claim.` },
]

phase('Review')
const reviewed = await pipeline(LENSES,
  l => agent(l.prompt, { label: `review:${l.key}`, phase: 'Review', schema: FINDINGS }),
  (r, l) => parallel((r?.findings || []).slice(0, 12).map(f => () =>
    parallel(['refute', 'reproduce'].map(mode => () =>
      agent(`${COMMON}\nA reviewer (${l.key} lens) reported: TITLE: ${f.title}\nSEVERITY: ${f.severity}\nWHERE: ${f.where}\nEVIDENCE: ${f.evidence}\nFIX: ${f.fix}\n` +
        (mode === 'refute' ? 'Try hard to REFUTE it: open the screenshot and/or the code and check whether the claim is actually true as stated. Default to refuted=true if you cannot confirm it.' :
          'Try to CONFIRM it independently: find the exact code and/or pixel evidence. Set refuted=true unless you can point at concrete evidence.'),
        { label: `verify:${l.key}:${f.title.slice(0, 28)}`, phase: 'Verify', schema: { type: 'object', properties: { refuted: { type: 'boolean' }, note: { type: 'string' } }, required: ['refuted', 'note'] } })))
      .then(vs => ({ ...f, lens: l.key, votes: vs.filter(Boolean), confirmed: vs.filter(Boolean).filter(v => !v.refuted).length >= 1 && vs.filter(Boolean).filter(v => v.refuted).length < 2 })))))
const all = reviewed.flat().filter(Boolean)
const confirmed = all.filter(f => f.confirmed)
log(`${all.length} findings, ${confirmed.length} confirmed`)
return { confirmed, rejected: all.filter(f => !f.confirmed).map(f => ({ title: f.title, lens: f.lens, notes: f.votes.map(v => v.note) })) }
