import Foundation

/// 一天练了多长落哪一档。热力图的颜色只由这一档决定，界面不再按分钟另算：
/// 有录音就点亮，五分钟以内最浅，五到十四分钟一档，十五到二十九分钟一档，三十分钟以上最深。
enum CheckInLevel: Int, CaseIterable {
    case none, light, steady, solid, deep
}

/// 热力图上的一格：本地日期、当日说过的音频总长，以及由这个长度推出来的档位。
struct CheckInDay {
    var date: Date
    var activeMs: Int64
    var level: CheckInLevel

    var minutes: Int { Int(activeMs / 60_000) }
}

/// 月份行的一条：第几列起字，写几个月。
struct CheckInMonthLabel: Identifiable {
    var column: Int
    var label: String
    var id: Int { column }
}

/// 二十六周乘七天的一块热力图。weeks 按列存，一列一周、行首是周一；
/// 末列走过今天的那些格子照画，但不进 activeDays 与 totalMs。
struct CheckInWeeks {
    static let columns = 26
    static let rows = 7

    var weeks: [[CheckInDay]]
    var activeDays: Int
    var totalMs: Int64

    /// 月份行只在跨月那一列起字；与上一条离得不足四列就跳过这一条，
    /// 两三个字挤在十来像素一列上谁也读不出来。
    func monthLabels(calendar: Calendar = .current) -> [CheckInMonthLabel] {
        var labels: [CheckInMonthLabel] = []
        var lastMonth = -1
        var lastColumn = -4
        for (column, days) in weeks.enumerated() {
            guard let first = days.first else { continue }
            let month = calendar.component(.month, from: first.date)
            if month == lastMonth { continue }
            lastMonth = month
            if column - lastColumn < 4 { continue }
            labels.append(CheckInMonthLabel(column: column, label: "\(month)月"))
            lastColumn = column
        }
        return labels
    }

    /// 卡片底下那一行汇总：几个真数，不写解释。
    var summary: String {
        let hours = totalMs / 3_600_000
        let minutes = (totalMs % 3_600_000) / 60_000
        let seconds = (totalMs % 60_000) / 1_000
        let body: String
        if hours > 0 {
            body = "\(hours) 时 \(minutes) 分"
        } else if minutes > 0 {
            body = "\(minutes) 分"
        } else {
            body = "\(seconds) 秒"
        }
        return "共 \(activeDays) 天 · \(body)"
    }
}

extension CheckInDay {
    /// 点中某一格时那一行的读数：日期加当天说过的长度，不足一分钟就写秒。
    var readout: String {
        let calendar = Calendar.current
        let month = calendar.component(.month, from: date)
        let day = calendar.component(.day, from: date)
        let head = "\(month)月\(day)日 · "
        if activeMs <= 0 { return head + "0 分" }
        if activeMs < 60_000 { return head + "\(activeMs / 1_000) 秒" }
        return head + "\(minutes) 分"
    }
}

/// 一轮说过的音频：这一轮落库的时刻，以及这段录音本身有多长。
struct SpokenTurn {
    var endedAt: Int64
    var durationMs: Int64
}

/// 一段会话里说过的那些轮次。分段的意义在于：跨会话的两段录音永远不会被合并成一块。
struct PracticeSession {
    var turns: [SpokenTurn]
}

/// 两段录音之间不超过这一截就算人还在练：中间在听、在想、在复述，这段空档一并计入。
/// 超过这一截说明已经走开，前后断成两块，空档不算。
let idleGapMs: Int64 = 5 * 60_000

/// 一段连着的练习：从这一块第一段录音开口，到最后一个字说完，中间只允许五分钟以内的空档。
struct PracticeBlock {
    var from: Int64
    var to: Int64
    var durationMs: Int64 { to - from }
}

extension PracticeSession {
    /// 把一段会话合成若干块练习。每轮录音是一个区间（落库时刻往前推录音长度就是开口的时刻），
    /// 相邻两个区间之间的空档不超过五分钟就并进同一块，超过就断开。
    ///
    /// 为什么按区间而不是按录音相加：一次对练里说话只占一部分，听模型说、想下一句都在练；
    /// 只要没走开，这段就整段算练习。走开两小时再回来，那两小时没有录音区间，
    /// 前后隔得太远也就不会并成一块，两小时不会被算成练习。
    func practiceBlocks() -> [PracticeBlock] {
        let spans = turns
            .filter { $0.durationMs > 0 }
            .map { (from: $0.endedAt - $0.durationMs, to: $0.endedAt) }
            .sorted { $0.from < $1.from }
        var blocks: [PracticeBlock] = []
        for span in spans {
            if let last = blocks.last, span.from - last.to <= idleGapMs {
                blocks[blocks.count - 1] = PracticeBlock(from: last.from, to: max(last.to, span.to))
            } else {
                blocks.append(PracticeBlock(from: span.from, to: span.to))
            }
        }
        return blocks
    }
}

/// 网格首列那个周一的零点：读库从这一刻起就够，早于它的一格都不会亮。
func checkInWindowStart(now: Int64, calendar: Calendar = .current, columns: Int = CheckInWeeks.columns) -> Int64 {
    let today = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1000))
    let monday = calendar.date(byAdding: .day, value: -((calendar.component(.weekday, from: today) + 5) % 7), to: today) ?? today
    let first = calendar.date(byAdding: .weekOfYear, value: -(columns - 1), to: monday) ?? monday
    return Int64(first.timeIntervalSince1970 * 1000)
}

func checkInLevel(durationMs: Int64) -> CheckInLevel {
    switch durationMs {
    case ...0: .none
    case 1..<(5 * 60_000): .light
    case (5 * 60_000)..<(15 * 60_000): .steady
    case (15 * 60_000)..<(30 * 60_000): .solid
    default: .deep
    }
}

func checkInWeeks(
    sessions: [PracticeSession],
    now: Int64,
    calendar: Calendar = .current,
    columns: Int = CheckInWeeks.columns
) -> CheckInWeeks {
    let nowDate = Date(timeIntervalSince1970: Double(now) / 1000)
    let today = calendar.startOfDay(for: nowDate)
    // 行首是周一：系统里周日是第一天的地区也要落到同一条线上。
    let weekdayIndex = (calendar.component(.weekday, from: today) + 5) % 7
    let lastMonday = calendar.date(byAdding: .day, value: -weekdayIndex, to: today) ?? today
    let firstMonday = calendar.date(byAdding: .weekOfYear, value: -(columns - 1), to: lastMonday) ?? lastMonday
    let windowStart = Int64(firstMonday.timeIntervalSince1970 * 1000)

    // 一块练习跨过零点就按日界切开，各归各家；先按毫秒累加再折成分钟，逐段取整会白扔掉。
    var millisByDay: [Date: Int64] = [:]
    for session in sessions {
        for block in session.practiceBlocks() {
            var cursor = max(block.from, windowStart)
            let until = min(block.to, now)
            while cursor < until {
                let day = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(cursor) / 1000))
                let midnight = Int64((calendar.date(byAdding: .day, value: 1, to: day) ?? day).timeIntervalSince1970 * 1000)
                let end = min(until, midnight)
                millisByDay[day, default: 0] += end - cursor
                cursor = end
            }
        }
    }

    var activeDays = 0
    var totalMs: Int64 = 0
    var weeks: [[CheckInDay]] = []
    for column in 0..<columns {
        let monday = calendar.date(byAdding: .weekOfYear, value: column, to: firstMonday) ?? firstMonday
        var days: [CheckInDay] = []
        for row in 0..<CheckInWeeks.rows {
            let date = calendar.date(byAdding: .day, value: row, to: monday) ?? monday
            let active = date > today ? 0 : (millisByDay[date] ?? 0)
            let level = checkInLevel(durationMs: active)
            if level != .none {
                activeDays += 1
                totalMs += active
            }
            days.append(CheckInDay(date: date, activeMs: active, level: level))
        }
        weeks.append(days)
    }
    return CheckInWeeks(weeks: weeks, activeDays: activeDays, totalMs: totalMs)
}
