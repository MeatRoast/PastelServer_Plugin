# SQLDB (Paper 1.21.1, Java 26)

MySQL 연결을 공용 서비스로 제공하는 Paper 플러그인입니다.

## 1) 설치/빌드

1. 이 프로젝트 빌드:
   - `gradle build` 또는 `./gradlew build`
2. 생성된 JAR를 Paper 1.21.1 서버 `plugins/`에 넣고 서버 시작
3. `plugins/SQLDB/config.yml`에서 MySQL 설정 수정 후 서버 재시작
4. 서버 실행 Java는 26으로 맞추기

## 2) 설정

`config.yml`

```yml
mysql:
  host: "127.0.0.1"
  port: 3306
  database: "minecraft"
  username: "root"
  password: "change-me"
  useSsl: false
  maximumPoolSize: 10
  connectionTimeoutMs: 10000
```

## 3) 호출(다른 플러그인 연동)

다른 플러그인 `plugin.yml`에 의존성 추가:

```yml
depend: [SQLDB]
```

다른 플러그인 코드에서 서비스 호출:

```java
import io.github.dohwan.sqldb.api.SqlDbApi;
import io.github.dohwan.sqldb.api.SqlDbService;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;

public final class OtherPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        SqlDbApi db = SqlDbService.get(this);
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1")) {
            ps.executeQuery();
        } catch (Exception e) {
            getLogger().severe("DB query failed: " + e.getMessage());
        }
    }
}
```

`/dbping` 명령어로 연결 상태를 확인할 수 있습니다.
