// 局域网 HTTP CONNECT 转发，开发期让真机借道本机网络访问实时语音服务。
// 用法：node tools/devproxy.mjs [port]
import net from "node:net";

const port = Number(process.argv[2] || 8889);
const log = (...args) => console.log(new Date().toISOString(), ...args);

const server = net.createServer({ allowHalfOpen: true }, (client) => {
  client.setNoDelay(true);
  let buffered = Buffer.alloc(0);
  let tunneled = false;

  const onData = (chunk) => {
    if (tunneled) return;
    buffered = Buffer.concat([buffered, chunk]);
    const end = buffered.indexOf("\r\n\r\n");
    if (end < 0) return;
    client.removeListener("data", onData);
    const head = buffered.subarray(0, end).toString();
    const rest = buffered.subarray(end + 4);
    const [method, target] = head.split("\r\n")[0].split(" ");
    if (method !== "CONNECT") {
      client.end("HTTP/1.1 405 Method Not Allowed\r\n\r\n");
      return;
    }
    const [host, rawPort] = target.split(":");
    const upstream = net.connect({ host, port: Number(rawPort || 443) }, () => {
      tunneled = true;
      client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      if (rest.length) upstream.write(rest);
      upstream.pipe(client, { end: false });
      client.pipe(upstream, { end: false });
    });
    const shutdown = (reason) => {
      log("tunnel closed", host, reason);
      upstream.destroy();
      client.destroy();
    };
    upstream.on("error", (e) => shutdown("upstream " + e.message));
    upstream.on("end", () => shutdown("upstream end"));
    client.on("error", (e) => shutdown("client " + e.message));
    client.on("end", () => shutdown("client end"));
  };

  client.on("data", onData);
  client.on("error", () => {});
});

server.listen(port, "0.0.0.0", () => log("connect-proxy listening on", port));
