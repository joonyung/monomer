import "ses";
import { transform as trustedTransform } from "sucrase";

Object.defineProperty(globalThis, "global", { value: globalThis, writable: false, configurable: false });
lockdown({ errorTaming: import.meta.env.DEV ? "unsafe-debug" : "safe" });
if (typeof trustedTransform !== "function") throw new Error("Table Worker compiler failed to initialize");
const pendingMessages: MessageEvent[] = [];
self.onmessage = event => pendingMessages.push(event);
void import("./runtime").then(({ startTableWidgetWorkerRuntime }) => {
  startTableWidgetWorkerRuntime();
  const receive = self.onmessage;
  for (const event of pendingMessages.splice(0)) receive?.call(self, event);
}).catch(() => {
  self.postMessage({ type: "failed", generation: -1, operationId: 0, message: "Table Worker failed to initialize" });
});
