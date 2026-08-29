/// \file json.hpp
/// \brief Minimal self-contained JSON parser (header-only).
///
/// Supports objects, arrays, strings, numbers, booleans and null — enough for
/// the bundled data files (portfolio.json, sbm_params.json, nmrf.json) and the
/// flat golden schema in data/golden/golden.json.  Objects preserve insertion
/// order (stored as a vector of key/value pairs), matching Python dict
/// semantics where the reference implementation relies on them.
///
/// Numbers are parsed with strtod, which is correctly rounded on glibc — the
/// same bits Python's json module produces.

#pragma once

#include <cctype>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace frtb {
namespace json {

/// A parsed JSON value (tagged union with value semantics).
class Value {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };
    using Array = std::vector<Value>;
    using Object = std::vector<std::pair<std::string, Value>>;

    Type type = Type::Null;
    bool boolean = false;
    double number = 0.0;
    std::string str;
    Array array;
    Object object;

    /// True when an object member with this key exists.
    bool has(const std::string& key) const {
        if (type != Type::Object) return false;
        for (const auto& kv : object)
            if (kv.first == key) return true;
        return false;
    }

    /// Object member access; throws std::invalid_argument when missing.
    const Value& at(const std::string& key) const {
        if (type != Type::Object)
            throw std::invalid_argument("json: value is not an object (key '" + key + "')");
        for (const auto& kv : object)
            if (kv.first == key) return kv.second;
        throw std::invalid_argument("json: key not found: '" + key + "'");
    }

    double as_number() const {
        if (type != Type::Number)
            throw std::invalid_argument("json: value is not a number");
        return number;
    }
    const std::string& as_string() const {
        if (type != Type::String)
            throw std::invalid_argument("json: value is not a string");
        return str;
    }
    bool is_number() const { return type == Type::Number; }
    bool is_string() const { return type == Type::String; }
};

/// Recursive-descent parser over a whole JSON document.
class Parser {
public:
    explicit Parser(const std::string& text) : s_(text) {}

    Value parse() {
        Value v = parse_value();
        skip_ws();
        if (pos_ != s_.size())
            throw std::invalid_argument("json: trailing characters after document");
        return v;
    }

private:
    const std::string& s_;
    std::size_t pos_ = 0;

    [[noreturn]] void fail(const std::string& what) const {
        throw std::invalid_argument("json: " + what + " at offset " + std::to_string(pos_));
    }
    void skip_ws() {
        while (pos_ < s_.size() &&
               (s_[pos_] == ' ' || s_[pos_] == '\t' || s_[pos_] == '\n' || s_[pos_] == '\r'))
            ++pos_;
    }
    char peek() {
        if (pos_ >= s_.size()) fail("unexpected end of input");
        return s_[pos_];
    }
    void expect(char c) {
        if (peek() != c) fail(std::string("expected '") + c + "'");
        ++pos_;
    }

    Value parse_value() {
        skip_ws();
        switch (peek()) {
            case '{': return parse_object();
            case '[': return parse_array();
            case '"': return parse_string();
            case 't': case 'f': return parse_bool();
            case 'n': return parse_null();
            default: return parse_number();
        }
    }

    Value parse_object() {
        Value v;
        v.type = Value::Type::Object;
        expect('{');
        skip_ws();
        if (peek() == '}') { ++pos_; return v; }
        while (true) {
            skip_ws();
            Value key = parse_string();
            skip_ws();
            expect(':');
            v.object.emplace_back(key.str, parse_value());
            skip_ws();
            if (peek() == ',') { ++pos_; continue; }
            expect('}');
            return v;
        }
    }

    Value parse_array() {
        Value v;
        v.type = Value::Type::Array;
        expect('[');
        skip_ws();
        if (peek() == ']') { ++pos_; return v; }
        while (true) {
            v.array.push_back(parse_value());
            skip_ws();
            if (peek() == ',') { ++pos_; continue; }
            expect(']');
            return v;
        }
    }

    Value parse_string() {
        Value v;
        v.type = Value::Type::String;
        expect('"');
        std::string out;
        while (true) {
            if (pos_ >= s_.size()) fail("unterminated string");
            char c = s_[pos_++];
            if (c == '"') break;
            if (c == '\\') {
                if (pos_ >= s_.size()) fail("bad escape");
                char e = s_[pos_++];
                switch (e) {
                    case '"': out += '"'; break;
                    case '\\': out += '\\'; break;
                    case '/': out += '/'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'n': out += '\n'; break;
                    case 'r': out += '\r'; break;
                    case 't': out += '\t'; break;
                    case 'u': {
                        if (pos_ + 4 > s_.size()) fail("bad \\u escape");
                        unsigned cp = std::stoul(s_.substr(pos_, 4), nullptr, 16);
                        pos_ += 4;
                        // Data files are ASCII; encode BMP code points as UTF-8.
                        if (cp < 0x80) {
                            out += static_cast<char>(cp);
                        } else if (cp < 0x800) {
                            out += static_cast<char>(0xC0 | (cp >> 6));
                            out += static_cast<char>(0x80 | (cp & 0x3F));
                        } else {
                            out += static_cast<char>(0xE0 | (cp >> 12));
                            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
                            out += static_cast<char>(0x80 | (cp & 0x3F));
                        }
                        break;
                    }
                    default: fail("unknown escape");
                }
            } else {
                out += c;
            }
        }
        v.str = out;
        return v;
    }

    Value parse_bool() {
        Value v;
        v.type = Value::Type::Bool;
        if (s_.compare(pos_, 4, "true") == 0) { v.boolean = true; pos_ += 4; return v; }
        if (s_.compare(pos_, 5, "false") == 0) { v.boolean = false; pos_ += 5; return v; }
        fail("bad literal");
    }

    Value parse_null() {
        if (s_.compare(pos_, 4, "null") == 0) { pos_ += 4; return Value{}; }
        fail("bad literal");
    }

    Value parse_number() {
        std::size_t start = pos_;
        while (pos_ < s_.size() &&
               (std::isdigit(static_cast<unsigned char>(s_[pos_])) || s_[pos_] == '-' ||
                s_[pos_] == '+' || s_[pos_] == '.' || s_[pos_] == 'e' || s_[pos_] == 'E'))
            ++pos_;
        if (pos_ == start) fail("expected a value");
        std::string tok = s_.substr(start, pos_ - start);
        char* end = nullptr;
        double d = std::strtod(tok.c_str(), &end);
        if (end != tok.c_str() + tok.size()) fail("bad number '" + tok + "'");
        Value v;
        v.type = Value::Type::Number;
        v.number = d;
        return v;
    }
};

/// Parse a JSON document from a string.
inline Value parse(const std::string& text) { return Parser(text).parse(); }

/// Parse a JSON document from a file; throws std::invalid_argument on I/O error.
inline Value parse_file(const std::string& path) {
    std::ifstream in(path);
    if (!in)
        throw std::invalid_argument("json: cannot open file '" + path + "'");
    std::ostringstream ss;
    ss << in.rdbuf();
    return parse(ss.str());
}

}  // namespace json
}  // namespace frtb
