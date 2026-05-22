import os
import re

def refactor_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    has_log_import = 'import android.util.Log;' in content
    has_log_calls = re.search(r'\bLog\.[deivw]\(', content)

    if not has_log_import and not has_log_calls:
        return

    # 1. Replace import
    if has_log_import:
        content = content.replace('import android.util.Log;', 'import timber.log.Timber;')
    elif 'import timber.log.Timber;' not in content and has_log_calls:
        # Add import if missing but Log calls exist
        package_match = re.search(r'package\s+.*?;', content)
        if package_match:
            content = content[:package_match.end()] + "\n\nimport timber.log.Timber;" + content[package_match.end():]

    # Find ALL TAG variable names (private or public)
    tag_match = re.search(r'(private|public)\s+static\s+final\s+String\s+TAG\s*=\s*.*?;', content)
    tag_name = "TAG" if tag_match else None

    log_levels = ['d', 'e', 'i', 'v', 'w']
    
    # Use re.DOTALL to match across multiple lines
    
    if tag_name:
        # 3-argument: Log.e(TAG, message, throwable)
        # We need to be careful with greediness. We search for the last comma before the last closing parenthesis.
        # But a simpler way is to match until the end of the statement, but that's also hard.
        # Let's try to match until the last closing parenthesis of the Log call.
        pattern_3arg = re.compile(rf'Log\.e\(\s*{tag_name}\s*,\s*(.*?),\s*([^,)]+)\s*\)', re.DOTALL)
        content = pattern_3arg.sub(r'Timber.e(\2, \1)', content)
        
        # 2-argument: Log.x(TAG, message)
        for level in log_levels:
            pattern_2arg = re.compile(rf'Log\.{level}\(\s*{tag_name}\s*,\s*(.*?)\s*\)', re.DOTALL)
            content = pattern_2arg.sub(rf'Timber.{level}(\1)', content)
    
    # Literal tags
    pattern_3arg_lit = re.compile(r'Log\.e\(\s*"(.*?)"\s*,\s*(.*?),\s*([^,)]+)\s*\)', re.DOTALL)
    content = pattern_3arg_lit.sub(r'Timber.e(\3, \2)', content)

    for level in log_levels:
        pattern_2arg_lit = re.compile(rf'Log\.{level}\(\s*"(.*?)"\s*,\s*(.*?)\s*\)', re.DOTALL)
        content = pattern_2arg_lit.sub(rf'Timber.{level}(\2)', content)

    # 3. Instruction 8: remove private TAG if used only for logging
    if tag_name:
        is_private = re.search(rf'private\s+static\s+final\s+String\s+{tag_name}\s*=', content)
        if is_private:
            occurrences = len(re.findall(rf'\b{tag_name}\b', content))
            if occurrences == 1:
                content = re.sub(rf'private\s+static\s+final\s+String\s+{tag_name}\s*=\s*.*?;', '', content)
                content = re.sub(r'\n\s*\n\s*\n', '\n\n', content)

    with open(file_path, 'w', encoding='utf-8', newline='') as f:
        f.write(content)

def main():
    root_dir = 'app/src/main/java'
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.java'):
                refactor_file(os.path.join(root, file))

if __name__ == '__main__':
    main()
