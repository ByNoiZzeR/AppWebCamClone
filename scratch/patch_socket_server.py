import os

def main():
    # 1. Read HTML_PAGE from the generated txt file
    with open('scratch/html_page_swift.txt', 'r', encoding='utf-8') as f:
        html_page_swift = f.read()
    
    # 2. Read SocketServer.swift
    swift_path = 'ios-client/ios-client/SocketServer.swift'
    with open(swift_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Insert closure variables
    target_start = 'var onStopStream: (() -> Void)?'
    closure_code = '\n    var onUpdateSetting: ((String, String) -> Void)?\n    var getStatusJSON: (() -> String)?'
    if target_start in content and 'onUpdateSetting' not in content:
        content = content.replace(target_start, target_start + closure_code)
        print("Inserted closures")
    
    # Insert getQueryParam helper and HTML_PAGE at the end of the class
    # We find the last '}' in the file
    last_brace_idx = content.rfind('}')
    helper_and_html = """
    private func getQueryParam(from urlString: String, for key: String) -> String? {
        guard let url = URL(string: "http://localhost" + urlString),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            return nil
        }
        return queryItems.first(where: { $0.name == key })?.value
    }
    
""" + html_page_swift
    
    if 'getQueryParam' not in content:
        content = content[:last_brace_idx] + helper_and_html + "\n}\n"
        print("Inserted helper and HTML page at the end")
    
    # Update parseHttpRequest to handle new endpoints
    target_parse = 'if uri == "/ping" {'
    new_endpoints = """if uri == "/" || uri.hasPrefix("/control") || uri.hasPrefix("/settings") {
            let body = HTML_PAGE
            let response = "HTTP/1.1 200 OK\\r\\nContent-Type: text/html; charset=utf-8\\r\\nContent-Length: \\(body.utf8.count)\\r\\nConnection: close\\r\\n\\r\\n\\(body)"
            sendResponse(connection, response: response, id: id)
            
        } else if uri.hasPrefix("/api/status") {
            let body = getStatusJSON?() ?? "{}"
            let response = "HTTP/1.1 200 OK\\r\\nContent-Type: application/json; charset=utf-8\\r\\nContent-Length: \\(body.utf8.count)\\r\\nConnection: close\\r\\n\\r\\n\\(body)"
            sendResponse(connection, response: response, id: id)
            
        } else if uri.hasPrefix("/api/set") {
            let key = getQueryParam(from: uri, for: "key") ?? ""
            let val = getQueryParam(from: uri, for: "val") ?? ""
            onUpdateSetting?(key, val)
            let body = "{\\"status\\":\\"success\\"}"
            let response = "HTTP/1.1 200 OK\\r\\nContent-Type: application/json; charset=utf-8\\r\\nContent-Length: \\(body.utf8.count)\\r\\nConnection: close\\r\\n\\r\\n\\(body)"
            sendResponse(connection, response: response, id: id)
            
        } else if uri == "/ping" {"""
    
    if 'hasPrefix("/api/status")' not in content:
        content = content.replace(target_parse, new_endpoints)
        print("Updated parseHttpRequest with web endpoints")
        
    # Write back the patched file
    with open(swift_path, 'w', encoding='utf-8') as f:
        f.write(content)
        
    print("SocketServer.swift successfully patched")

if __name__ == '__main__':
    main()
