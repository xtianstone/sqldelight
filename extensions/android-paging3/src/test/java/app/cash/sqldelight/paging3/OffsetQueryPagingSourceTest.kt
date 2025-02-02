/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.sqldelight.paging3

import androidx.paging.PagingSource.LoadParams.Refresh
import androidx.paging.PagingSource.LoadResult
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class OffsetQueryPagingSourceTest {

  private lateinit var driver: SqlDriver
  private lateinit var transacter: Transacter

  @Before fun before() {
    driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    driver.execute(null, "CREATE TABLE testTable(value INTEGER PRIMARY KEY)", 0)
    (0L until 10L).forEach { this.insert(it) }
    transacter = object : TransacterImpl(driver) {}
  }

  @Test fun `empty page gives correct prevKey and nextKey`() {
    driver.execute(null, "DELETE FROM testTable", 0)
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(null, 2, false)) }

    assertNull((results as LoadResult.Page).prevKey)
    assertNull(results.nextKey)
  }

  @Test fun `aligned first page gives correct prevKey and nextKey`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(null, 2, false)) }

    assertNull((results as LoadResult.Page).prevKey)
    assertEquals(2, results.nextKey)
  }

  @Test fun `aligned last page gives correct prevKey and nextKey`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(8, 2, false)) }

    assertEquals(6, (results as LoadResult.Page).prevKey)
    assertNull(results.nextKey)
  }

  @Test fun `simple sequential page exhaustion gives correct results`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    runBlocking {
      val expected = (0 until 10).chunked(2).iterator()
      var nextKey: Int? = null
      do {
        val results = source.load(Refresh(nextKey, 2, false))
        assertEquals(expected.next(), (results as LoadResult.Page).data)
        nextKey = results.nextKey
        1L.toInt()
      } while (nextKey != null)
    }
  }

  @Test fun `misaligned refresh at end page boundary gives null nextKey`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(9, 2, false)) }

    assertEquals(7, (results as LoadResult.Page).prevKey)
    assertNull(results.nextKey)
  }

  @Test fun `misaligned refresh at first page boundary gives proper prevKey`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(1, 2, false)) }

    assertEquals(-1, (results as LoadResult.Page).prevKey)
    assertEquals(3, results.nextKey)
  }

  @Test fun `initial page has correct itemsBefore and itemsAfter`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(null, 2, false)) }

    assertEquals(0, (results as LoadResult.Page).itemsBefore)
    assertEquals(8, results.itemsAfter)
  }

  @Test fun `middle page has correct itemsBefore and itemsAfter`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(4, 2, false)) }

    assertEquals(4, (results as LoadResult.Page).itemsBefore)
    assertEquals(4, results.itemsAfter)
  }

  @Test fun `end page has correct itemsBefore and itemsAfter`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(8, 2, false)) }

    assertEquals(8, (results as LoadResult.Page).itemsBefore)
    assertEquals(0, results.itemsAfter)
  }

  @Test fun `misaligned end page has correct itemsBefore and itemsAfter`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(9, 2, false)) }

    assertEquals(9, (results as LoadResult.Page).itemsBefore)
    assertEquals(0, results.itemsAfter)
  }

  @Test fun `misaligned start page has correct itemsBefore and itemsAfter`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    val results = runBlocking { source.load(Refresh(1, 2, false)) }

    assertEquals(1, (results as LoadResult.Page).itemsBefore)
    assertEquals(7, results.itemsAfter)
  }

  @Test fun `prepend paging misaligned start page produces correct values`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    runBlocking {
      val expected = listOf(listOf(1, 2), listOf(0)).iterator()
      var prevKey: Int? = 1
      do {
        val results = source.load(Refresh(prevKey, 2, false))
        assertEquals(expected.next(), (results as LoadResult.Page).data)
        prevKey = results.prevKey
      } while (prevKey != null)
    }
  }

  @Test fun `key too big throws IndexOutOfBoundsException`() {
    val source = OffsetQueryPagingSource(
      this::query,
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    runBlocking {
      assertFailsWith<IndexOutOfBoundsException> {
        source.load(Refresh(10, 2, false))
      }
    }
  }

  @Test fun `query invalidation invalidates paging source`() {
    val query = query(2, 0)
    val source = OffsetQueryPagingSource(
      { _, _ -> query },
      countQuery(),
      transacter,
      EmptyCoroutineContext,
    )

    runBlocking { source.load(Refresh(null, 0, false)) }

    driver.notifyListeners(arrayOf("testTable"))

    assertTrue(source.invalid)
  }

  private fun query(limit: Int, offset: Int) = object : Query<Int>(
    { cursor -> cursor.getLong(0)!!.toInt() },
  ) {
    override fun <R> execute(mapper: (SqlCursor) -> R) = driver.executeQuery(1, "SELECT value FROM testTable LIMIT ? OFFSET ?", mapper, 2) {
      bindLong(0, limit.toLong())
      bindLong(1, offset.toLong())
    }

    override fun addListener(listener: Listener) = driver.addListener(listener, arrayOf("testTable"))
    override fun removeListener(listener: Listener) = driver.removeListener(listener, arrayOf("testTable"))
  }

  private fun countQuery() = Query(
    2,
    arrayOf("testTable"),
    driver,
    "Test.sq",
    "count",
    "SELECT count(*) FROM testTable",
    { it.getLong(0)!!.toInt() },
  )

  private fun insert(value: Long, db: SqlDriver = driver) {
    db.execute(0, "INSERT INTO testTable (value) VALUES (?)", 1) {
      bindLong(0, value)
    }
  }
}
