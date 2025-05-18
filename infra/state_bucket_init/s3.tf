resource "aws_s3_bucket" "state" {
  bucket = "shithead-game-state-bucket"
  tags = {
    Name = "Terraform state for shithead-backend"
  }
}

resource "aws_s3_bucket_ownership_controls" "state_ownership" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "state_block" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
