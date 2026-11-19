package db

import (
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
)

// NewProvider gives tests a goose provider over the embedded migrations, to migrate to a given version.
func NewProvider(pool *pgxpool.Pool) (*goose.Provider, error) {
	return goose.NewProvider(goose.DialectPostgres, stdlib.OpenDBFromPool(pool), mustSub(migrations, "migrations"))
}
