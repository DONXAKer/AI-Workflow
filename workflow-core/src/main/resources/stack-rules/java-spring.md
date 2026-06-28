# Java / Spring Boot — обязательные требования кодирования

## Dependency Injection
- Autowiring ИСКЛЮЧИТЕЛЬНО через конструктор: `private final` поля + явный конструктор или `@RequiredArgsConstructor` (Lombok).
- Никакого `@Autowired` на полях или setter-методах.
- Исключение: `@Autowired` на методе `@Bean` в `@Configuration` классах — это Spring pattern и допустимо.

## Общие правила
- `@Service`, `@Component`, `@Repository` — всегда `final` класс если нет прокирования.
- Не смешивать `@Transactional` на уровне поля и метода.
- Логировать через `LoggerFactory.getLogger(ClassName.class)`, не через Lombok `@Slf4j` если используется plain Java.
