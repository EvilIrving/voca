import Foundation

func jsonOf(_ raw: String) -> [String: Any]? {
    guard let data = raw.data(using: .utf8) else { return nil }
    return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
}

func obj(_ value: [String: Any], _ key: String) -> [String: Any]? {
    value[key] as? [String: Any]
}

func array(_ value: [String: Any], _ key: String) -> [[String: Any]]? {
    value[key] as? [[String: Any]]
}

func stringField(_ value: [String: Any], _ key: String) -> String? {
    value[key] as? String
}

func doubleField(_ value: [String: Any], _ key: String) -> Double? {
    if let d = value[key] as? Double { return d }
    if let i = value[key] as? Int { return Double(i) }
    if let s = value[key] as? String, let d = Double(s) { return d }
    return nil
}

func intField(_ value: [String: Any], _ key: String) -> Int? {
    if let i = value[key] as? Int { return i }
    if let d = value[key] as? Double { return Int(d) }
    if let s = value[key] as? String, let i = Int(s) { return i }
    return nil
}