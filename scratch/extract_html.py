def main():
    with open('android-client/app/src/main/java/com/webcamclone/SocketServer.java', 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    html_lines = []
    started = False
    for line in lines:
        if 'private static final String HTML_PAGE =' in line:
            started = True
            continue
        if started:
            stripped = line.strip()
            if not stripped:
                continue
            # If the line doesn't start with a quote, it might be the end of the HTML_PAGE block
            if not (stripped.startswith('"') or stripped.startswith('+')):
                break
            
            # Find the string literal within double quotes
            # We find the first and last double quotes
            first_q = stripped.find('"')
            last_q = stripped.rfind('"')
            if first_q != -1 and last_q != -1 and first_q != last_q:
                val = stripped[first_q+1:last_q]
                # Unescape Java escapes
                val = val.replace('\\n', '\n').replace('\\t', '\t').replace('\\"', '"').replace('\\\\', '\\')
                html_lines.append(val)
    
    html_content = "".join(html_lines)
    swift_code = f'    private let HTML_PAGE = """\n{html_content}"""\n'
    
    with open('scratch/html_page_swift.txt', 'w', encoding='utf-8') as f_out:
        f_out.write(swift_code)
    
    print("Successfully generated. Length of HTML:", len(html_content))

if __name__ == '__main__':
    main()
