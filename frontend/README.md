# frontend — monitoring dashboard

**Status:** not implemented (Milestone 4).

React + TypeScript single-page app that talks only to the
[backend](../backend/) REST API. It shows registered models, monitoring runs,
per-feature drift results and reference vs. current distributions.

## Planned layout

```text
frontend/
├── package.json
├── src/
│   ├── api/         typed client for backend contracts
│   ├── pages/       models, model detail, run detail
│   ├── components/  tables, charts, status badges
│   └── test/
└── vite.config.ts
```

## Planned dependencies

| Package | Purpose |
|---------|---------|
| react, react-dom | UI |
| typescript | Static typing (strict mode) |
| vite | Dev server and build |
| react-router | Routing |
| a charting library (e.g. Recharts) | Distribution and trend charts — chosen in task 4.5 |
| vitest, @testing-library/react | Unit and component tests |
| eslint, prettier | Linting and formatting |

Node.js 20+.
