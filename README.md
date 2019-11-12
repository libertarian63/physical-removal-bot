# physical-removal-bot
## Сборка 
```bash
sbt assembly
```

## Запуск
После сборки в target/scala-2.12 появится исполняемый файл с помощью которого можно запустить бота:

```
Usage: java -jar physical-removal-bot --token <string> [--socksProxy] [--host <string>] [--port <string>]

Bot for physical removing spamers from telegram chat

Options and flags:
    --help
        Display this help text.
    --token <string>
        Telegram bot token.
    --socksProxy
        Use SOCKS proxy. By default used tor proxy with default host and port
    --host <string>
        Proxy host.
    --port <string>
        Proxy port.
```