import http from 'k6/http';
import { check } from 'k6';

// Submission throughput and latency: closed-loop constant VUs hammering
// POST /api/v1/jobs. Run with the rate limit raised (see RESULTS.md) so the
// limiter's Lua call is measured but its policy doesn't cap the result.
const API = __ENV.API || 'http://localhost:8080';
const KEY = __ENV.API_KEY || 'dev-key';

export const options = {
    scenarios: {
        submit: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 20 },
                { duration: '60s', target: 20 },
                { duration: '5s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const res = http.post(`${API}/api/v1/jobs`,
        JSON.stringify({ type: 'sleep', payload: { millis: 0 } }),
        { headers: { 'Content-Type': 'application/json', 'X-API-Key': KEY } });
    check(res, { 'created': (r) => r.status === 201 });
}
