#!/usr/bin/env node
// Start the gateway. Configuration: the JSON file named by VF_SYNC_CONFIG
// (default /config/config.json). See ../README.md.

import http from "node:http";
import fs from "node:fs";
import { createGateway, hardenServer } from "../src/gateway.mjs";

const file = process.env.VF_SYNC_CONFIG || "/config/config.json";
const config = JSON.parse(fs.readFileSync(file, "utf8"));
for (const k of ["dataRoot", "tokensFile", "stateDir"]) {
	if (typeof config[k] !== "string" || config[k] === "") {
		console.error(`vf-sync: config "${k}" is required`);
		process.exit(2);
	}
}
const host = config.listen?.host ?? "0.0.0.0";
const port = config.listen?.port ?? 8790;

const { handler } = await createGateway(config);
const server = hardenServer(http.createServer(handler));
server.listen(port, host, () => {
	process.stdout.write(`${JSON.stringify({ t: new Date().toISOString(), event: "listening", port })}\n`);
});

const stop = () => {
	server.close(() => process.exit(0));
	setTimeout(() => process.exit(0), 10_000).unref();
};
process.on("SIGTERM", stop);
process.on("SIGINT", stop);
