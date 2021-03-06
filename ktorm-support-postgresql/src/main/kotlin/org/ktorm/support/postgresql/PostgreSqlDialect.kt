/*
 * Copyright 2018-2020 the original author or authors.
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

package org.ktorm.support.postgresql

import org.ktorm.database.Database
import org.ktorm.database.SqlDialect
import org.ktorm.expression.*
import org.ktorm.schema.IntSqlType

/**
 * [SqlDialect] implementation for PostgreSQL database.
 */
public open class PostgreSqlDialect : SqlDialect {

    override fun createSqlFormatter(database: Database, beautifySql: Boolean, indentSize: Int): SqlFormatter {
        return PostgreSqlFormatter(database, beautifySql, indentSize)
    }
}

/**
 * [SqlFormatter] implementation for PostgreSQL, formatting SQL expressions as strings with their execution arguments.
 */
public open class PostgreSqlFormatter(
    database: Database,
    beautifySql: Boolean,
    indentSize: Int
) : SqlFormatter(database, beautifySql, indentSize) {

    override fun checkColumnName(name: String) {
        val maxLength = database.maxColumnNameLength
        if (maxLength > 0 && name.length > maxLength) {
            throw IllegalStateException("The identifier '$name' is too long. Maximum length is $maxLength")
        }
    }

    override fun visit(expr: SqlExpression): SqlExpression {
        val result = when (expr) {
            is InsertOrUpdateExpression -> visitInsertOrUpdate(expr)
            else -> super.visit(expr)
        }

        check(result === expr) { "SqlFormatter cannot modify the expression trees." }
        return result
    }

    override fun <T : Any> visitScalar(expr: ScalarExpression<T>): ScalarExpression<T> {
        val result = when (expr) {
            is ILikeExpression -> visitILike(expr)
            is HStoreExpression -> visitHStore(expr)
            else -> super.visitScalar(expr)
        }

        @Suppress("UNCHECKED_CAST")
        return result as ScalarExpression<T>
    }

    override fun writePagination(expr: QueryExpression) {
        newLine(Indentation.SAME)

        if (expr.limit != null) {
            writeKeyword("limit ? ")
            _parameters += ArgumentExpression(expr.limit, IntSqlType)
        }
        if (expr.offset != null) {
            writeKeyword("offset ? ")
            _parameters += ArgumentExpression(expr.offset, IntSqlType)
        }
    }

    protected open fun visitILike(expr: ILikeExpression): ILikeExpression {
        if (expr.left.removeBrackets) {
            visit(expr.left)
        } else {
            write("(")
            visit(expr.left)
            removeLastBlank()
            write(") ")
        }

        writeKeyword("ilike ")

        if (expr.right.removeBrackets) {
            visit(expr.right)
        } else {
            write("(")
            visit(expr.right)
            removeLastBlank()
            write(") ")
        }

        return expr
    }

    protected open fun <T : Any> visitHStore(expr: HStoreExpression<T>): HStoreExpression<T> {
        if (expr.left.removeBrackets) {
            visit(expr.left)
        } else {
            write("(")
            visit(expr.left)
            removeLastBlank()
            write(") ")
        }

        writeKeyword("${expr.type} ")

        if (expr.right.removeBrackets) {
            visit(expr.right)
        } else {
            write("(")
            visit(expr.right)
            removeLastBlank()
            write(") ")
        }

        return expr
    }

    protected open fun visitInsertOrUpdate(expr: InsertOrUpdateExpression): InsertOrUpdateExpression {
        writeKeyword("insert into ")
        write("${expr.table.name.quoted} (")
        for ((i, assignment) in expr.assignments.withIndex()) {
            if (i > 0) write(", ")
            checkColumnName(assignment.column.name)
            write(assignment.column.name.quoted)
        }
        writeKeyword(") values (")
        visitExpressionList(expr.assignments.map { it.expression as ArgumentExpression })
        removeLastBlank()
        writeKeyword(") on conflict (")
        for ((i, column) in expr.conflictTarget.withIndex()) {
            if (i > 0) write(", ")
            checkColumnName(column.name)
            write(column.name.quoted)
        }
        writeKeyword(") do update set ")
        for ((i, assignment) in expr.updateAssignments.withIndex()) {
            if (i > 0) {
                removeLastBlank()
                write(", ")
            }
            checkColumnName(assignment.column.name)
            write("${assignment.column.name.quoted} ")
            write("= ")
            visit(assignment.expression)
        }
        return expr
    }
}

/**
 * Base class designed to visit or modify PostgreSQL's expression trees using visitor pattern.
 *
 * For detailed documents, see [SqlExpressionVisitor].
 */
public open class PostgreSqlExpressionVisitor : SqlExpressionVisitor() {

    override fun visit(expr: SqlExpression): SqlExpression {
        return when (expr) {
            is InsertOrUpdateExpression -> visitInsertOrUpdate(expr)
            else -> super.visit(expr)
        }
    }

    override fun <T : Any> visitScalar(expr: ScalarExpression<T>): ScalarExpression<T> {
        val result = when (expr) {
            is ILikeExpression -> visitILike(expr)
            is HStoreExpression -> visitHStore(expr)
            else -> super.visitScalar(expr)
        }

        @Suppress("UNCHECKED_CAST")
        return result as ScalarExpression<T>
    }

    protected open fun visitILike(expr: ILikeExpression): ILikeExpression {
        val left = visitScalar(expr.left)
        val right = visitScalar(expr.right)

        if (left === expr.left && right === expr.right) {
            return expr
        } else {
            return expr.copy(left = left, right = right)
        }
    }

    protected open fun <T : Any> visitHStore(expr: HStoreExpression<T>): HStoreExpression<T> {
        val left = visitScalar(expr.left)
        val right = visitScalar(expr.right)

        if (left === expr.left && right === expr.right) {
            return expr
        } else {
            return expr.copy(left = left, right = right)
        }
    }

    protected open fun visitInsertOrUpdate(expr: InsertOrUpdateExpression): InsertOrUpdateExpression {
        val table = visitTable(expr.table)
        val assignments = visitColumnAssignments(expr.assignments)
        val conflictTarget = visitExpressionList(expr.conflictTarget)
        val updateAssignments = visitColumnAssignments(expr.updateAssignments)

        @Suppress("ComplexCondition")
        if (table === expr.table
            && assignments === expr.assignments
            && conflictTarget === expr.conflictTarget
            && updateAssignments === expr.updateAssignments) {
            return expr
        } else {
            return expr.copy(
                table = table,
                assignments = assignments,
                conflictTarget = conflictTarget,
                updateAssignments = updateAssignments
            )
        }
    }
}
