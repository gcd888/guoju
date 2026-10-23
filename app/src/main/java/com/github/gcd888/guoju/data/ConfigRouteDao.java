package com.github.gcd888.guoju.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 配置路由表 DAO：配置弹窗渲染、推送自动应用、设置页联查的数据源。
 * 行序=rowid（插入序），即配置树扫描序——「第一条线路」语义依赖它。
 */
@Dao
public interface ConfigRouteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ConfigRoute> rows);

    /** 整组覆盖第一步（推送/刷新）：删除该配置全部旧行。 */
    @Query("DELETE FROM config_route WHERE configUrl = :configUrl")
    void deleteByConfigUrl(String configUrl);

    /** 某配置全部路由行（插入序 = 配置树扫描序）。 */
    @Query("SELECT * FROM config_route WHERE configUrl = :configUrl ORDER BY rowid ASC")
    List<ConfigRoute> getByConfigUrl(String configUrl);

    /** 该配置是否已有路由行（弹窗判断是否需要扫描）。 */
    @Query("SELECT COUNT(*) FROM config_route WHERE configUrl = :configUrl")
    int countByConfigUrl(String configUrl);
}
