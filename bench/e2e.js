import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// End-to-end latency: submit one job, poll until SUCCEEDED, record the
// wall-clock time from submission to completion. Light load on purpose —
// this measures the queue's delivery latency, not its saturation point.
const API = __ENV.API || 'http://localhost:8080';
const KEY = __ENV.API_KEY || 'dev-key';
const HEADERS = { 'Content-Type': 'application/json', 'X-API-Key': KEY };

const e2eLatency = new Trend('e2e_latency', true);

export const options = {
    scenarios: {
        e2e: {
            executor: 'constant-vus',
            vus: 5,
            duration: '60s',
        },
    },
    thresholds: {
        checks: ['rate>0.99'],
    },
};

export default function () {
    const started = Date.now();
    const submitted = http.post(`${API}/api/v1/jobs`,
        JSON.stringify({ type: 'sleep', payload: { millis: 0 } }),
        { headers: HEADERS });
    if (!check(submitted, { 'created': (r) => r.status === 201 })) {
        return;
    }

    const id = JSON.parse(submitted.body).id;
    const deadline = Date.now() + 30_000;
    let completed = false;
    while (Date.now() < deadline) {
        const job = http.get(`${API}/api/v1/jobs/${id}`, { headers: HEADERS });
        if (job.status === 200 && JSON.parse(job.body).status === 'SUCCEEDED') {
            completed = true;
            break;
        }
        sleep(0.05);
    }

    check(null, { 'completed within 30s': () => completed });
    if (completed) {
        e2eLatency.add(Date.now() - started);
    }
}
