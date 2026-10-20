resource "aws_dynamodb_table" "authorization" {

  name = "${var.name}-financial-authorization"
  billing_mode = "PAY_PER_REQUEST"
  hash_key = "pk"
  range_key = "sk"
  attribute {
 name = "pk"
 type = "S" 
}

  attribute {
 name = "sk"
 type = "S" 
}

  stream_enabled = true
  stream_view_type = "NEW_IMAGE"
  point_in_time_recovery {
 enabled = true 
}

  server_side_encryption {
 enabled = true 
}

  deletion_protection_enabled = true
  # ttl deliberately disabled until records' regulatory/audit retention is specified

}

resource "aws_dynamodb_table" "accounting" {

  name = "${var.name}-accounting-processing"
  billing_mode = "PAY_PER_REQUEST"
  hash_key = "pk"
  range_key = "sk"
  attribute {
 name = "pk"
 type = "S" 
}

  attribute {
 name = "sk"
 type = "S" 
}

  point_in_time_recovery {
 enabled = true 
}

  server_side_encryption {
 enabled = true 
}

  deletion_protection_enabled = true

}

