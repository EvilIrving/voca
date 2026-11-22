import Foundation

/// 打字打断在跑的生成时，服务端先收掉那一轮再回新的一句。实测顺序是：这句 clientContent 推上去，
/// 几十毫秒后服务端给 interrupted，紧跟一条 turnComplete，然后才是新一句的音频与转写。
///
/// 那段尾巴和那个收尾都不属于新的一句。尾巴要是照旧送上去，旧一轮的字会并进新气泡里；
/// 收尾要是照旧送上去，界面会当成新一句已经说完，回到空闲态，新一句真正的回复随后到达时
/// 全被丢在空闲态里，看起来就是打完字没有回音。
///
/// 服务端要是一直没给 interrupted，说明这一轮其实没被打断，收尾照旧放行；窗口到点也放行，
/// 新的一句不会被永远关在外面。
final class InterruptedGeneration {
    private let windowMs: Int64
    private let now: () -> Int64

    private var until: Int64 = 0
    private var interrupted = false

    init(windowMs: Int64, now: @escaping () -> Int64) {
        self.windowMs = windowMs
        self.now = now
    }

    /// 在跑的生成途中冒出一次新的输入：从这一刻起认这段尾巴。
    func arm() {
        until = now() + windowMs
        interrupted = false
    }

    /// 服务端确认打断：这一轮的收尾可以准备吞掉了。
    func interrupt() {
        if active() { interrupted = true }
    }

    /// 尾巴：打断之后到达的旧轮转写与音频不该算到新的一句头上。
    func swallowing() -> Bool {
        active()
    }

    /// 收尾：确实被打断过的那一轮给的那一条，吞掉它并停止吞。
    func boundary() -> Bool {
        if !active() || !interrupted { return false }
        until = 0
        interrupted = false
        return true
    }

    private func active() -> Bool {
        if until == 0 { return false }
        if now() >= until {
            until = 0
            interrupted = false
            return false
        }
        return true
    }
}
