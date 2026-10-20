resource "aws_schemas_registry" "domain" {
  name = "${var.name}-event-schemas"
  description = "POD99 - esquemas dos eventos financeiros de dominio"
}
resource "aws_schemas_schema" "transaction_authorized" {
  name = "TransacaoAutorizada"
  registry_name = aws_schemas_registry.domain.name
  type = "JSONSchemaDraft4"
  content = file("${path.module}/schemas/transacao-autorizada.schema.json")
  description = "Contrato v1 CloudEvents TransacaoAutorizada"
}
