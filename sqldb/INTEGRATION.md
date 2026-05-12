# SQLDB 연동 가이드

이 문서는 **다른 Paper 플러그인**에서 SQLDB를 공용 DB 서비스로 사용하는 방법입니다.

## 1) SQLDB 준비

1. 서버 `plugins/`에 `sqldb-1.0.0.jar` 넣기
2. 서버 1회 실행해서 `plugins/SQLDB/config.yml` 생성
3. MySQL 접속정보 설정
4. 서버 재시작
5. `/dbping` 또는 `/dbsql start`로 연결 확인

## 2) 연동 플러그인 의존성 선언

연동 플러그인 `plugin.yml`:

```yml
name: YourPlugin
main: your.package.YourPlugin
version: 1.0.0
api-version: "1.21"
depend: [SQLDB]
```

`depend`를 쓰면 SQLDB가 먼저 로드됩니다.

## 3) 연동 플러그인 빌드 의존성

SQLDB 프로젝트를 빌드하면 아래 2개 산출물이 생깁니다.

- `build/libs/sqldb-1.0.0.jar` (실행 플러그인)
- `build/libs/sqldb-1.0.0-api.jar` (연동용 API)

연동 플러그인 `build.gradle.kts` 예시:

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly(files("libs/sqldb-1.0.0-api.jar"))
}
```

## 4) 코드에서 서비스 호출

```java
import io.github.dohwan.sqldb.api.SqlDbApi;
import io.github.dohwan.sqldb.api.SqlDbService;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class YourPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        SqlDbApi db = SqlDbService.get(this);

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                getLogger().info("DB OK: " + rs.getInt(1));
            }
        } catch (Exception e) {
            getLogger().warning("DB query failed: " + e.getMessage());
        }
    }
}
```

## 5) 운영 명령어

SQLDB 관리:

- `/dbsql start` : DB 연결 시작
- `/dbsql stop` : DB 연결 중지
- `/dbsql reload` : config 재로드 + DB 재연결
- `/dbping` : 연결 상태 확인

## 6) 장애 동작

- MySQL 연결 실패해도 SQLDB 플러그인은 활성화 유지
- 콘솔 경고 출력
- 경고/응답 메시지는 `plugins/SQLDB/config.yml`의 `messages`에서 커스텀 가능
