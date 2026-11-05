# tools: фиксированный transport diagnostic

Наследует [общие Java-соглашения](../AGENTS.md).

## Класс и API

[OnionSmoke](OnionSmoke.java) выполняет один явный запрос к закреплённому onion HOST.
Это диагностический entrypoint, используемый также CLI как источник HOST/URL;
здесь нет orchestration, scheduler, admission или robots acceptance.

[OnionSmoke.java](OnionSmoke.java):

```java
static Config configuration();
static String save(ProtocolResponse response, Path output) throws IOException;
public static void main(String[] args) throws Exception;
```


## Контракты и эффекты

- configuration возвращает Storm Config: Tor ONLY, SOCKS 127.0.0.1:19050,
  allowlist HOST, timeout 30000 ms, body limit 131072 и HTML-only.
- main не принимает аргументов/target overrides, создаёт .runtime/downloads,
  делает TorProtocol GET и вызывает cleanup в finally.
- save требует HTTP 200, непустой bounded text/html; JSoup извлекает title,
  Files.write сохраняет ahmia.html с существующей семантикой замены.
  Не приписывайте этому helper no-overwrite контракт HtmlFiles.save.
- main сохраняет ahmia-smoke.json и печатает JSON даже при ошибке, затем повторно
  выбрасывает исходный failure. Цепочка причин присутствует в диагностическом результате.
- Этот GET не подтверждает crawl completion, robots permission, Tor recovery
  или отсутствие всех DNS-утечек. Тесты save не инициируют live Tor-запрос.

## Границы

Зависимости: Kotlin TorProtocol, Storm Config/Metadata/ProtocolResponse, JSoup и Jackson.
Сохраняйте фиксированную цель, ограничения и порядок cleanup/evidence.
Прикладной запуск находится в [admission](../admission/AGENTS.md);
повторяемый UI относится к [cli](../cli/AGENTS.md).
Имена, четыре пробела и русские Javadocs: [общие соглашения](../AGENTS.md).
