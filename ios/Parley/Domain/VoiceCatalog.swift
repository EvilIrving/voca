import Foundation

enum VoiceCatalog {
    static func voices(_ vendor: LiveVendor) -> [VoiceOption] {
        switch vendor {
        case .gemini: gemini
        case .openAi: openai
        case .doubao: doubao
        }
    }

    static func defaultId(_ vendor: LiveVendor) -> String {
        voices(vendor).first { $0.isDefault }?.id ?? voices(vendor)[0].id
    }

    static func groups(_ vendor: LiveVendor) -> [(String, [VoiceOption])] {
        var ordered: [(String, [VoiceOption])] = []
        for option in voices(vendor) {
            if let index = ordered.firstIndex(where: { $0.0 == option.group }) {
                ordered[index].1.append(option)
            } else {
                ordered.append((option.group, [option]))
            }
        }
        return ordered
    }

    static func isKnown(_ vendor: LiveVendor, id: String) -> Bool {
        voices(vendor).contains { $0.id == id }
    }

    private static let geminiGroup = "Live 预置"
    private static let openaiGroup = "内置音色"
    private static let doubaoO = "O 系列"
    private static let doubaoSC = "SC 系列"
    private static let doubaoSC2 = "SC 2.0 系列"

    private static let gemini: [VoiceOption] = [
        .init(id: "Zephyr", style: "明亮", group: geminiGroup),
        .init(id: "Puck", style: "活泼", group: geminiGroup),
        .init(id: "Charon", style: "稳重", group: geminiGroup),
        .init(id: "Kore", style: "坚定", group: geminiGroup),
        .init(id: "Fenrir", style: "激昂", group: geminiGroup),
        .init(id: "Leda", style: "年轻", group: geminiGroup),
        .init(id: "Orus", style: "坚定", group: geminiGroup),
        .init(id: "Aoede", style: "轻快", isDefault: true, group: geminiGroup),
        .init(id: "Callirrhoe", style: "随和", group: geminiGroup),
        .init(id: "Autonoe", style: "明亮", group: geminiGroup),
        .init(id: "Enceladus", style: "气声", group: geminiGroup),
        .init(id: "Iapetus", style: "清晰", group: geminiGroup),
        .init(id: "Umbriel", style: "随和", group: geminiGroup),
        .init(id: "Algieba", style: "顺滑", group: geminiGroup),
        .init(id: "Despina", style: "顺滑", group: geminiGroup),
        .init(id: "Erinome", style: "清晰", group: geminiGroup),
        .init(id: "Algenib", style: "沙哑", group: geminiGroup),
        .init(id: "Rasalgethi", style: "信息型", group: geminiGroup),
        .init(id: "Laomedeia", style: "活泼", group: geminiGroup),
        .init(id: "Achernar", style: "柔和", group: geminiGroup),
        .init(id: "Alnilam", style: "坚定", group: geminiGroup),
        .init(id: "Schedar", style: "平稳", group: geminiGroup),
        .init(id: "Gacrux", style: "成熟", group: geminiGroup),
        .init(id: "Pulcherrima", style: "外向", group: geminiGroup),
        .init(id: "Achird", style: "友好", group: geminiGroup),
        .init(id: "Zubenelgenubi", style: "随性", group: geminiGroup),
        .init(id: "Vindemiatrix", style: "温柔", group: geminiGroup),
        .init(id: "Sadachbia", style: "生动", group: geminiGroup),
        .init(id: "Sadaltager", style: "博学", group: geminiGroup),
        .init(id: "Sulafat", style: "温暖", group: geminiGroup),
    ]

    private static let openai: [VoiceOption] = [
        .init(id: "alloy", style: "中性", isDefault: true, group: openaiGroup),
        .init(id: "ash", style: "沉稳", group: openaiGroup),
        .init(id: "ballad", style: "叙事", group: openaiGroup),
        .init(id: "coral", style: "明亮", group: openaiGroup),
        .init(id: "echo", style: "低沉", group: openaiGroup),
        .init(id: "sage", style: "温和", group: openaiGroup),
        .init(id: "shimmer", style: "轻软", group: openaiGroup),
        .init(id: "verse", style: "亲切", group: openaiGroup),
        .init(id: "marin", style: "自然", group: openaiGroup),
        .init(id: "cedar", style: "厚实", group: openaiGroup),
    ]

    private static let doubao: [VoiceOption] = [
        .init(id: "zh_female_vv_jupiter_bigtts", style: "中文女声 · 多情感，日常对话", isDefault: true, group: doubaoO),
        .init(id: "zh_female_xiaohe_jupiter_bigtts", style: "中文女声 · 温柔，长对话陪伴", group: doubaoO),
        .init(id: "zh_male_yunzhou_jupiter_bigtts", style: "中文男声 · 沉稳，讲解与叙事", group: doubaoO),
        .init(id: "zh_male_xiaotian_jupiter_bigtts", style: "中文男声 · 活泼，日常闲聊", group: doubaoO),
        .init(id: "en_male_tim_uranus_bigtts", style: "英文男声 · 自然，英文对练", group: doubaoO),
        .init(id: "zh_female_cancan_mars_bigtts", style: "中文女声 · 通用，播报与客服", group: doubaoSC),
        .init(id: "zh_female_shuangkuaisisi_moon_bigtts", style: "中文女声 · 爽快，节奏偏快", group: doubaoSC),
        .init(id: "ICL_zh_female_keainvsheng_tob", style: "中文女声 · 声音复刻，SC 独有", group: doubaoSC),
        .init(id: "saturn_zh_female_keainvsheng_tob", style: "中文女声 · 甜美人设，角色扮演", group: doubaoSC2),
        .init(id: "saturn_zh_male_tiancaitongsheng_tob", style: "中文男声 · 少年童声", group: doubaoSC2),
        .init(id: "saturn_zh_female_tiaopigongzhu_tob", style: "中文女声 · 俏皮公主", group: doubaoSC2),
        .init(id: "saturn_zh_male_lengjundashu_tob", style: "中文男声 · 冷峻大叔", group: doubaoSC2),
    ]
}
