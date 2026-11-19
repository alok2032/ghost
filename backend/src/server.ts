import { createZeroTrustApp } from "./app.js";
import { assertProductionConfig, loadConfig } from "./config.js";
import { logSecurityEvent } from "./services/auditLogger.js";

const config = loadConfig();
assertProductionConfig(config);
const app = createZeroTrustApp({ config });

app.listen(config.port, () => {
  logSecurityEvent({
    severity: "INFO",
    category: "SERVER_BOOT",
    message: `AYRA Zero-Trust Backend listening on port ${config.port} (env=${config.nodeEnv}, requireAppCheck=${config.requireAppCheck}, project=${config.firebaseProjectId})`,
  });
});
